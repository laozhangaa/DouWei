package com.laozhang.mockgps.server

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.laozhang.mockgps.R
import com.amap.api.maps.model.LatLng
import com.blankj.utilcode.util.ToastUtils
import com.laozhang.mockgps.manager.FloatingViewManger
import com.laozhang.mockgps.manager.SearchManager
import com.laozhang.mockgps.manager.NavigationRecoveryStore
import com.laozhang.mockgps.model.MockMessageModel
import com.laozhang.mockgps.model.NaviType
import com.laozhang.mockgps.model.PoiInfoModel
import com.laozhang.mockgps.model.PoiInfoType
import com.laozhang.mockgps.model.RouteCoordinate
import com.laozhang.mockgps.model.RecoveryRoute
import com.laozhang.mockgps.model.PlaybackPosition
import com.laozhang.mockgps.model.NavigationOptions
import com.laozhang.mockgps.model.NavigationProgress
import com.laozhang.mockgps.model.RouteMetadata
import com.laozhang.mockgps.model.FavoritePoint
import com.laozhang.mockgps.model.SimulatedStop
import com.laozhang.mockgps.model.TravelMode
import com.laozhang.mockgps.model.SimulationStatus
import com.laozhang.mockgps.model.SimulationLocation
import com.laozhang.mockgps.utils.CalculationLogLatDistance
import com.laozhang.mockgps.utils.LocationUtils
import com.laozhang.mockgps.utils.MMKVUtils
import com.laozhang.mockgps.ui.MainActivity

/**
 * @author jiayu.liu
 */
class GpsService : Service() {
    companion object {
        const val ACTION_PAUSE = "com.laozhang.mockgps.action.PAUSE"
        const val ACTION_RESUME = "com.laozhang.mockgps.action.RESUME"
        const val ACTION_STOP = "com.laozhang.mockgps.action.STOP"
        const val ACTION_SEEK = "com.laozhang.mockgps.action.SEEK"
        private const val START_MOCK_LOCATION = 1001
        private const val START_MOCK_NAVI = 1002
        private const val START_MOCK_FILE_NAVI = 1003
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL = "mock_location"
        // Reuse one queue across service instances so old cleanup always precedes new registration.
        private val locationWorker: Handler by lazy {
            val thread = HandlerThread("MockLocation", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
            Handler(thread.looper)
        }

        fun launch(context: Context, intent: Intent) {
            if (intent.hasExtra("info")) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }
    }

    private var isStart = false
    private lateinit var pump: MockLocationPump<MockLocationSnapshot>
    private var pumpGeneration = 0L
    private var pumpRunning = false
    private var destroyed = false
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepErrorOnDestroy = false
    private lateinit var handle: Handler
    private val uiHandler = Handler(Looper.getMainLooper())
    private var model: MockMessageModel? = null
    private var index = 0
    private var routePlayback: RoutePlayback? = null
    private var recoveryRoute: RecoveryRoute? = null
    private var restorePosition: PlaybackPosition? = null
    private var routeFinished = false
    private var progressPublishedAt = 0L
    private var injectionStartedAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!isStart || destroyed) return
            val last = InjectionDiagnostics.lastAcceptedNanos.takeIf { it > 0 } ?: injectionStartedAt
            if (last > 0 && SystemClock.elapsedRealtimeNanos() - last > 5_000_000_000L) {
                failSimulation("系统定位写入已中断，请检查定位权限和省电限制后继续")
            } else uiHandler.postDelayed(this, 1_000L)
        }
    }
    // Only the injection worker reads/writes these UI throttling fields.
    private val deliveryGate = SnapshotDeliveryGate()
    private val providerStr: String = LocationManager.GPS_PROVIDER
    private val networkStr: String = LocationManager.NETWORK_PROVIDER
    private var bearing: Float = 0.0f

    /**
     * 米/S
     */
    @Volatile private var mSpeed: Float = 60 / 3.6f
    private var mCurrentLocation: LatLng? = null
    private var naviType: Int = NaviType.NONE
    private var isLocationAdjust = false

    /**
     * 模拟导航点更新间隔  单位：ms  小于等于1000ms
     */
    private var mNaviUpdateValue = 1000L

    override fun onCreate() {
        super.onCreate()
        handle = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                //重置为默认值
                mNaviUpdateValue = 1000L
                when (msg.what) {
                    START_MOCK_LOCATION -> {
                        if (isStart) {
                            (msg.obj as PoiInfoModel?)?.latLng?.let {
                                if (mCurrentLocation == it && isLocationQuiver()) {
                                    CalculationLogLatDistance.getRandomLatLng(
                                        it,
                                        MMKVUtils.getLocationVibrationValue().toDouble()
                                    ).apply {
                                        //仅震动模式时，可以配置震动的频率
                                        mNaviUpdateValue =
                                            MMKVUtils.getLocationFrequencyValue() * 1000L
                                        startSimulateLocation(this, true)
                                    }
                                } else {
                                    mCurrentLocation = it
                                    startSimulateLocation(it, true)
                                }
                                if (isStart) {
                                    handle.sendMessageDelayed(Message.obtain(msg), mNaviUpdateValue)
                                }
                            }
                        }
                    }

                    START_MOCK_NAVI, START_MOCK_FILE_NAVI -> {
                        if (isStart) {
                            (msg.obj as ArrayList<*>?)?.let {
                                if (it.isEmpty()) {
                                    failSimulation("路线数据为空，请重新规划路线")
                                    return
                                }
                                startRoutePlayback(it.map { point -> point as LatLng })
                            }
                        }
                    }

                    else -> {
                    }
                }

            }
        }
        initLocationManager()
    }

    private fun initLocationManager() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val fixFactory = MockFixFactory()
        val worker = locationWorker
        val session = MockProviderSession(object : MockProviderSession.Backend<MockLocationSnapshot> {
            override fun providers(): List<String> = listOf(providerStr, networkStr).let {
                if (manager.allProviders.contains("fused")) it + "fused" else it
            }.also { InjectionDiagnostics.providerCount = it.size }

            override fun register(provider: String) {
                val profile = MockProviderProfile.forProvider(provider)
                val power = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (profile.highPower) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW
                } else {
                    if (profile.highPower) Criteria.POWER_HIGH else Criteria.POWER_LOW
                }
                val accuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (profile.fineAccuracy) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE
                } else {
                    if (profile.fineAccuracy) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
                }
                manager.addTestProvider(
                    provider, profile.requiresNetwork, profile.requiresSatellite,
                    profile.requiresCell, profile.hasCost, true, true, true, power, accuracy
                )
            }

            override fun setEnabled(provider: String, enabled: Boolean) =
                manager.setTestProviderEnabled(provider, enabled)

            override fun write(provider: String, location: MockLocationSnapshot) {
                val fix = fixFactory.create(MockProviderProfile.forProvider(provider), location,
                    System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
                manager.setTestProviderLocation(provider, Location(provider).apply {
                    latitude = location.latitude
                    longitude = location.longitude
                    altitude = location.altitude
                    bearing = location.bearing
                    speed = location.speed
                    accuracy = fix.accuracy
                    time = fix.timeMillis
                    elapsedRealtimeNanos = fix.elapsedRealtimeNanos
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = fix.verticalAccuracy
                        speedAccuracyMetersPerSecond = fix.speedAccuracy
                        bearingAccuracyDegrees = fix.bearingAccuracy
                    }
                    fix.satellites?.let { count -> extras = Bundle().apply { putInt("satellites", count) } }
                })
            }

            override fun remove(provider: String) = manager.removeTestProvider(provider)
        })
        pump = MockLocationPump(object : MockLocationPump.Scheduler {
            override fun post(task: Runnable) { worker.post(task) }
            override fun postDelayed(task: Runnable, delayMillis: Long) { worker.postDelayed(task, delayMillis) }
            override fun remove(task: Runnable) { worker.removeCallbacks(task) }
        }, session, onInjected = { ticket, snapshot ->
            val now = SystemClock.elapsedRealtimeNanos()
            val report = deliveryGate.accept(ticket, now, snapshot.progress?.finished == true)
            if (report) {
                uiHandler.post {
                    if (!destroyed && isStart && ticket == pumpGeneration) {
                        snapshot.routeIndex?.let { next ->
                            index = next
                            mCurrentLocation = snapshot.routePoint?.let { LatLng(it.latitude, it.longitude) }
                                ?: LatLng(snapshot.displayLatitude, snapshot.displayLongitude)
                            bearing = snapshot.bearing
                            FloatingViewManger.INSTANCE.updateNaviInfo(next, snapshot.routeSize ?: 0)
                        }
                        snapshot.progress?.let { progress ->
                            if (progress.finished || now - progressPublishedAt >= 1_000_000_000L) {
                                progressPublishedAt = now
                                SimulationStateStore.publishProgress(progress)
                            }
                            persistProgress(progress.finished)
                        }
                        val starting = SimulationStateStore.current.status == SimulationStatus.STARTING
                        SimulationStateStore.publish(SimulationStateStore.current.locationInjected())
                        SimulationStateStore.publishLocation(SimulationLocation(
                            snapshot.displayLatitude, snapshot.displayLongitude, 3f, snapshot.bearing
                        ))
                        FloatingViewManger.INSTANCE.setCurLocation(LatLng(snapshot.displayLatitude, snapshot.displayLongitude))
                        FloatingViewManger.INSTANCE.startMock()
                        if (starting) updateForeground()
                    }
                }
            }
        }, onFailure = { ticket, error ->
            Log.e("MockGps", "Mock location worker failed", error)
            uiHandler.post {
                if (!destroyed && isStart && ticket == pumpGeneration) {
                    failSimulation(if (error is SecurityException)
                        "模拟定位权限已失效，请在开发者选项中重新选择豆位"
                        else "模拟位置写入失败，请检查系统定位设置后重试")
                }
            }
        }, onCleanupFailure = { error -> Log.e("MockGps", "Unable to remove all mock providers", error) },
            onHeartbeat = { InjectionDiagnostics.accepted(SystemClock.elapsedRealtimeNanos()) })
    }

    private fun initFloatingView() {
        //浮动窗
        FloatingViewManger.INSTANCE.run {
            if (!isAddFloatingView) addFloatViewToWindow()
            listener = object : FloatingViewManger.FloatingViewListener {
                override fun pause() {
                    pauseSimulation()
                }

                override fun reStart(isRest: Boolean) {
                    resumeSimulation(isRest)
                }

                override fun getNaviType(): Int {
                    return naviType
                }

                override fun changeLocation(latLng: LatLng, angle: Double) {
                    if (model == null) {
                        return
                    }
                    isLocationAdjust = true
                    bearing = angle.toFloat()
                    sendMockLocation(latLng)
                }

                override fun locationAdjustFinish() {
                    isLocationAdjust = false
                }

                override fun changeNaviInfo(index: Int, speed: Int) {
                    MMKVUtils.setSpeed(speed)
                    mSpeed = speed / 3.6f
                    model?.speed = speed
                    recoveryRoute?.takeIf { it.speedKmH != speed }?.let { route ->
                        recoveryRoute = route.copy(speedKmH = speed)
                        NavigationRecoveryStore.start(recoveryRoute!!, routePlayback?.position() ?: PlaybackPosition())
                    }
                    //index==-1 仅调整速度
                    if (index >= 0) {
                        SearchManager.INSTANCE.polylineList.let {
                            if (index < it.size) {
                                mCurrentLocation = it[index]
                                this@GpsService.index = index
                                routePlayback?.seek(index)
                            }
                        }
                    }
                    if (!isStart) {
                        reStart(false)
                    }
                }

                override fun switchLocation() {
                    if (model == null) {
                        return
                    }
                    //将模拟导航切换成模拟定位
                    model?.naviType = NaviType.LOCATION
                    recoveryRoute = null
                    routePlayback = null
                    NavigationRecoveryStore.clear()
                    SimulationStateStore.publishProgress(null)
                    sendMockLocation(mCurrentLocation)
                }

                /**
                 * 发送模拟定位Handler
                 */
                private fun sendMockLocation(location: LatLng?) {
                    if (location == null) {
                        ToastUtils.showShort("mock数据异常，此次模拟触发失败！")
                        return
                    }
                    PoiInfoModel(
                        latLng = location,
                        poiInfoType = PoiInfoType.LOCATION,
                    ).also {
                        model?.locationModel = it
                        sendHandler(
                            START_MOCK_LOCATION,
                            it
                        )
                    }
                }

            }
        }
    }

    private fun startRoutePlayback(points: List<LatLng>) {
        val options = model?.navigationOptions ?: NavigationOptions()
        val stops = if (TravelMode.fromId(model?.travelMode) == TravelMode.DRIVING)
            SearchManager.INSTANCE.selectedMetadata.estimatedStopMeters.map { SimulatedStop(it, options.stopSeconds) }
            else emptyList()
        val playback = routePlayback ?: RoutePlayback(points.map {
            RouteCoordinate(it.latitude, it.longitude)
        }, options, stops).also {
            val restored = restorePosition
            if (restored != null) it.restore(restored) else it.seek(index)
            restorePosition = null
            routePlayback = it
        }
        val pointType = model?.pointType ?: LocationUtils.gcj02
        var firstTick = true
        val source = {
            // Runs on the injection worker after any old session has finished cleaning up.
            if (firstTick) {
                playback.resetClock()
                firstTick = false
            }
            val frame = playback.advance(SystemClock.elapsedRealtimeNanos(), mSpeed)
            createSnapshot(LatLng(frame.coordinate.latitude, frame.coordinate.longitude),
                frame.speed, frame.bearing, pointType).copy(routeIndex = frame.nextIndex,
                routeSize = points.size, routePoint = frame.coordinate,
                progress = NavigationProgress(frame.distanceMeters, frame.totalMeters, frame.speed * 3.6f,
                    frame.remainingSeconds, frame.stopRemainingSeconds, frame.finished))
        }
        val initial = createSnapshot(mCurrentLocation ?: points.first(), mSpeed, bearing, pointType)
        monitorInjection()
        pumpGeneration = pump.start(initial, source)
        pumpRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEEK -> {
                val fraction = intent.getDoubleExtra("seek_fraction", Double.NaN)
                val playback = routePlayback
                if (playback != null && fraction.isFinite() && fraction in 0.0..1.0) {
                    playback.seekFraction(fraction)
                    routeFinished = false
                    val frame = playback.advance(SystemClock.elapsedRealtimeNanos(), mSpeed)
                    mCurrentLocation = LatLng(frame.coordinate.latitude, frame.coordinate.longitude)
                    index = frame.nextIndex
                    SimulationStateStore.publishProgress(NavigationProgress(frame.distanceMeters, frame.totalMeters,
                        if (isStart) frame.speed * 3.6f else 0f, frame.remainingSeconds, frame.stopRemainingSeconds, frame.finished))
                    persistProgress(frame.finished, true)
                }
            }
            ACTION_PAUSE -> pauseSimulation()
            ACTION_RESUME -> {
                if (SimulationStateStore.current.status == SimulationStatus.PAUSED) {
                    resumeSimulation()
                } else if (model == null) {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                recoveryRoute = null
                NavigationRecoveryStore.clear()
                handle.removeCallbacksAndMessages(null)
                removeGps()
                keepErrorOnDestroy = false
                SimulationStateStore.publish(SimulationStateStore.current.stop())
                stopSelf()
            }
            else -> {
                if (intent?.hasExtra("info") == true) updateForeground()
                initFloatingView()
                // The guide may start the floating controls without starting a session.
                intent?.getParcelableExtra<MockMessageModel>("info")?.let {
                    // New targets must not inherit old messages or enabled providers.
                    handle.removeCallbacksAndMessages(null)
                    removeGps()
                    model = it
                    keepErrorOnDestroy = false
                    mCurrentLocation = null
                    routePlayback = null
                    recoveryRoute = null
                    restorePosition = null
                    routeFinished = false
                    progressPublishedAt = 0L
                    bearing = 0f
                    SimulationStateStore.clearLocation()
                    FloatingViewManger.INSTANCE.stopMock()
                    try {
                        prepareRecovery(it)
                        mockLocation()
                    } catch (error: Exception) {
                        failSimulation(error.message ?: "路线恢复失败，请重新规划")
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun mockLocation() {
        model?.run {
            when (naviType) {
                NaviType.LOCATION -> {
                    sendHandler(START_MOCK_LOCATION, locationModel)
                }

                NaviType.NAVI, NaviType.NAVI_FILE -> {
                    mSpeed = speed / 3.6f
                    //算路成功后 startService
                    index = 0
                    SearchManager.INSTANCE.polylineList.let {
                        sendHandler(
                            if (naviType == NaviType.NAVI_FILE) START_MOCK_FILE_NAVI else START_MOCK_NAVI,
                            it
                        )
                    }
                }

                else -> failSimulation("模拟模式无效，请重新选择")
            }
        } ?: run {
            isStart = false
        }
    }

    private fun pauseSimulation() {
        val state = SimulationStateStore.current
        if (state.status != SimulationStatus.RUNNING && state.status != SimulationStatus.STARTING) return
        handle.removeCallbacksAndMessages(null)
        removeGps()
        persistProgress(force = true)
        SimulationStateStore.publish(state.pause())
        updateForeground()
    }

    private fun resumeSimulation(resetRoute: Boolean = false) {
        val session = model ?: return
        if (session.naviType == NaviType.LOCATION) {
            mockLocation()
        } else {
            if (resetRoute) {
                index = 0
                routePlayback?.seek(0)
            }
            sendHandler(
                if (session.naviType == NaviType.NAVI_FILE) START_MOCK_FILE_NAVI else START_MOCK_NAVI,
                SearchManager.INSTANCE.polylineList
            )
        }
    }

    private fun sessionTarget(): String = model?.run {
        if (naviType == NaviType.LOCATION) {
            locationModel?.name?.takeIf { it.isNotBlank() }
                ?: locationModel?.latLng?.let { "${it.latitude}, ${it.longitude}" }.orEmpty()
        } else {
            listOfNotNull(startNavi?.name, endNavi?.name)
                .filter { it.isNotBlank() }.joinToString(" → ")
                .ifBlank { "沿已选路线模拟" }
        }
    }.orEmpty()

    private fun failSimulation(message: String) {
        InjectionDiagnostics.lastError = message
        persistProgress(force = true)
        handle.removeCallbacksAndMessages(null)
        removeGps()
        keepErrorOnDestroy = true
        SimulationStateStore.publish(SimulationStateStore.current.fail(message))
        ToastUtils.showShort(message)
        stopSelf()
    }

    private fun sendHandler(code: Int, model: Any?) {
        if (code == START_MOCK_LOCATION && (model as? PoiInfoModel)?.latLng == null) {
            failSimulation("模拟位置为空，请重新选择地址")
            return
        }
        if (code != START_MOCK_LOCATION && (model as? ArrayList<*>)?.isNotEmpty() != true) {
            failSimulation("路线数据为空，请重新规划路线")
            return
        }
        val msg: Message = Message.obtain().apply {
            what = code
            obj = model
        }
        when (code) {
            START_MOCK_LOCATION -> this.naviType = NaviType.LOCATION
            START_MOCK_NAVI -> this.naviType = NaviType.NAVI
            START_MOCK_FILE_NAVI -> this.naviType = NaviType.NAVI_FILE
            else -> {
                this.naviType = NaviType.NONE
            }
        }

        // STARTING is distinct from RUNNING until Android accepts the first location.
        val state = SimulationStateStore.current
        val target = sessionTarget()
        SimulationStateStore.publish(
            if (pumpRunning && isStart) state.copy(mode = naviType, target = target)
            else if (state.status == SimulationStatus.PAUSED && state.mode == naviType && state.target == target)
                state.resume() else state.start(naviType, target)
        )
        try {
            isStart = true
            handle.removeCallbacksAndMessages(null)
            updateForeground()
            acquireWakeLock()
            // Prepare the first snapshot now; all Android provider calls run on the worker.
            handle.dispatchMessage(msg)
        } catch (e: Exception) {
            e.printStackTrace()
            failSimulation(if (e is SecurityException)
                "请在开发者选项中将豆位设为模拟位置信息应用"
                else "无法启用模拟定位，请检查系统定位设置后重试")
        } finally {
            msg.recycle()
        }
    }


    override fun onDestroy() {
        persistProgress(force = true)
        destroyed = true
        handle.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacksAndMessages(null)
        removeGps()
        stopForeground(true)
        foreground = false
        FloatingViewManger.INSTANCE.onDestroy()
        FloatingViewManger.INSTANCE.listener = null
        naviType = NaviType.NONE
        if (!keepErrorOnDestroy) SimulationStateStore.publish(SimulationStateStore.current.stop())
        super.onDestroy()
    }


    private fun startSimulateLocation(latLng: LatLng, isSingle: Boolean) {
        val pointType = model?.pointType ?: LocationUtils.gcj02
        val snapshot = createSnapshot(latLng, if (isSingle) 0f else mSpeed, bearing, pointType)
        if (pumpRunning) pump.update(snapshot) else {
            monitorInjection()
            pumpGeneration = pump.start(snapshot)
            pumpRunning = true
        }
    }

    private fun createSnapshot(latLng: LatLng, speed: Float, bearing: Float, pointType: String): MockLocationSnapshot {
        var gps84 = doubleArrayOf(latLng.longitude, latLng.latitude)
        when (pointType) {
            LocationUtils.gcj02 -> {
                gps84 = LocationUtils.gcj02ToWGS84(latLng.longitude, latLng.latitude)
            }

            LocationUtils.bd09 -> {
                gps84 = LocationUtils.bd09ToWGS84(latLng.longitude, latLng.latitude)
            }

            else -> {}
        }

        val displayPoint = if (pointType == LocationUtils.gcj02) latLng else {
            LocationUtils.wgs84ToGcj02(gps84[0], gps84[1]).let { LatLng(it[1], it[0]) }
        }
        return MockLocationSnapshot(
            gps84[1], gps84[0], displayPoint.latitude, displayPoint.longitude,
            speed,
            bearing
        )
    }

    private fun removeGps() {
        isStart = false
        pumpRunning = false
        pumpGeneration = pump.stop()
        uiHandler.removeCallbacks(watchdog)
        InjectionDiagnostics.active = false
        InjectionDiagnostics.providerCount = 0
        releaseWakeLock()
        FloatingViewManger.INSTANCE.stopMock()
    }

    private fun monitorInjection() {
        InjectionDiagnostics.starting()
        injectionStartedAt = SystemClock.elapsedRealtimeNanos()
        uiHandler.removeCallbacks(watchdog)
        uiHandler.postDelayed(watchdog, 1_000L)
    }

    private fun prepareRecovery(message: MockMessageModel) {
        if (message.naviType != NaviType.NAVI && message.naviType != NaviType.NAVI_FILE) return
        message.navigationOptions = message.navigationOptions ?: MMKVUtils.getNavigationOptions()
        message.navigationOptions?.validate()
        val restored = message.recoveryId?.let { id ->
            NavigationRecoveryStore.cached?.takeIf { it.route.id == id }
                ?: error("上次导航恢复记录已变更，请返回首页重新选择")
        }
        if (restored != null) {
            restored.route.validate()
            SearchManager.INSTANCE.polylineList = ArrayList(restored.route.path.map { LatLng(it.latitude, it.longitude) })
            SearchManager.INSTANCE.selectedMetadata = restored.route.metadata
            message.pointType = restored.route.metadata.coordinateSystem
            message.navigationOptions = restored.route.options
            message.speed = restored.route.speedKmH
            message.travelMode = restored.route.travelMode
            recoveryRoute = restored.route
            restorePosition = restored.position
            NavigationRecoveryStore.start(restored.route, restored.position)
            return
        }
        val metadata = if (message.naviType == NaviType.NAVI_FILE) RouteMetadata(provider = "FILE",
            coordinateSystem = message.pointType ?: LocationUtils.gcj02) else SearchManager.INSTANCE.selectedMetadata
        SearchManager.INSTANCE.selectedMetadata = metadata
        val points = (listOfNotNull(message.startNavi) + message.wayNaviList.orEmpty() + listOfNotNull(message.endNavi))
            .mapNotNull { point -> point.latLng?.let { FavoritePoint(point.name.orEmpty(), it.latitude, it.longitude) } }
        val route = RecoveryRoute(target = sessionTarget(), points = points,
            path = SearchManager.INSTANCE.polylineList.map { RouteCoordinate(it.latitude, it.longitude) },
            travelMode = TravelMode.fromId(message.travelMode).name, speedKmH = message.speed,
            metadata = metadata, options = message.navigationOptions ?: NavigationOptions())
        // Oversized imported files can still play; they are explicitly excluded from recovery.
        if (route.path.size <= 50_000) {
            route.validate()
            recoveryRoute = route
            NavigationRecoveryStore.start(route)
        } else {
            NavigationRecoveryStore.clear()
            ToastUtils.showLong("文件路线超过 5 万个节点，本次可模拟，但无法保存退出恢复进度")
        }
    }

    private fun persistProgress(finished: Boolean = routeFinished, force: Boolean = false) {
        val route = recoveryRoute ?: return
        val position = routePlayback?.position() ?: return
        if (finished) {
            if (!routeFinished) NavigationRecoveryStore.clear()
            routeFinished = true
        } else {
            if (NavigationRecoveryStore.cached?.route?.id != route.id) NavigationRecoveryStore.start(route, position)
            NavigationRecoveryStore.update(route.id, position, force)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:simulation")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun updateForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL, "模拟定位", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val state = SimulationStateStore.current
        val paused = state.status == SimulationStatus.PAUSED
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        fun action(action: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, GpsService::class.java).setAction(action), flags)
        val text = when (state.status) {
            SimulationStatus.PAUSED -> "已暂停模拟"
            SimulationStatus.RUNNING -> "正在模拟位置"
            else -> "正在启动模拟"
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_simulation_notification)
            .setContentTitle("豆位 · $text")
            .setContentText(state.target.ifBlank { "点击返回豆位" })
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (paused) "继续" else "暂停",
                action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "停止", action(ACTION_STOP, 2))
            .build()
        if (foreground) manager.notify(NOTIFICATION_ID, notification) else {
            startForeground(NOTIFICATION_ID, notification)
            foreground = true
        }
    }

    /**
     * 是否处于定位震动状态
     */
    private fun isLocationQuiver(): Boolean {
        return MMKVUtils.isLocationVibrationSwitch() && !isLocationAdjust
    }

}
