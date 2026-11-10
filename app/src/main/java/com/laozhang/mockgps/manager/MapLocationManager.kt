package com.laozhang.mockgps.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.model.LatLng
import com.baidu.location.BDLocation
import com.baidu.location.BDLocationListener
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.blankj.utilcode.util.LogUtils
import com.laozhang.mockgps.model.SimulationLocation
import com.laozhang.mockgps.model.SimulationState
import com.laozhang.mockgps.server.SimulationStateStore

enum class FollowMode { MODE_SINGLE, MODE_PERSISTENT, MODE_NONE }
enum class Source { FLOATING, OTHER }

class MapLocationManager(context: Context, private val map: AppMap,
                         private val follow: FollowMode,
                         private val source: Source = Source.OTHER,
                         private val preferSimulation: Boolean = source == Source.FLOATING) {
    private var amapClient: AMapLocationClient? = null
    private var baiduClient: LocationClient? = null
    private var zoomed = false
    private var destroyed = false
    private var followMode = follow
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var currentLocationCallback: ((Result<LatLng>) -> Unit)? = null
    private val requestTimeout = Runnable {
        finishCurrentLocation(Result.failure(IllegalStateException("定位超时，请检查系统定位和网络后重试")))
    }
    private val baiduListener = BDLocationListener { location ->
        if (location != null && location.locType in listOf(
                BDLocation.TypeGpsLocation, BDLocation.TypeNetWorkLocation, BDLocation.TypeOffLineLocation)) {
            update(LatLng(location.latitude, location.longitude), location.radius, location.direction,
                location.locType != BDLocation.TypeOffLineLocation)
        } else if (location != null) {
            finishCurrentLocation(Result.failure(IllegalStateException("百度定位失败(${location.locType})，请检查定位权限和网络")))
        }
    }
    private val simulationLocationObserver = Observer<SimulationLocation?> { location ->
        if (location != null && SimulationStateStore.current.hasSession) {
            update(LatLng(location.latitude, location.longitude), location.accuracy, location.bearing,
                fromSimulation = true)
        }
    }
    private val simulationStateObserver = Observer<SimulationState> { state ->
        if (state.hasSession) {
            // SDK network fixes must not compete with the position being simulated.
            stopClients()
            SimulationStateStore.location.value?.let { simulationLocationObserver.onChanged(it) }
        } else startClient()
    }

    init {
        map.enableLocation()
        if (preferSimulation) {
            SimulationStateStore.state.observeForever(simulationStateObserver)
            SimulationStateStore.location.observeForever(simulationLocationObserver)
        } else startClient()
    }

    private fun startClient() {
        if (destroyed || (preferSimulation && SimulationStateStore.current.hasSession)) return
        try {
            check(MapSdkCredentials.initialize(map.provider)) { "请先在设置中配置地图服务凭证" }
            if (map.provider == MapProvider.BAIDU) {
                if (baiduClient != null) return
                baiduClient = LocationClient(appContext).apply {
                    locOption = LocationClientOption().apply {
                        setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy)
                        setCoorType("gcj02")
                        setScanSpan(1000)
                        setOpenGps(true)
                        setIsNeedAddress(false)
                        setEnableSimulateGps(true)
                        disableCache(true)
                        setWifiCacheTimeOut(0)
                        setTimeOut(12000)
                    }
                    registerLocationListener(baiduListener)
                    start()
                }
            } else {
                if (amapClient != null) return
                amapClient = AMapLocationClient(appContext).apply {
                    setLocationOption(AMapLocationClientOption().apply {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                        interval = 1000
                        isNeedAddress = false
                        isMockEnable = true
                        isOffset = true
                        isLocationCacheEnable = false
                        httpTimeOut = 12000
                    })
                    setLocationListener { location ->
                        if (location != null && location.errorCode == 0) {
                            update(LatLng(location.latitude, location.longitude), location.accuracy, location.bearing)
                        } else if (location != null) {
                            finishCurrentLocation(Result.failure(IllegalStateException(
                                "高德定位失败(${location.errorCode})，请检查定位权限和网络")))
                        }
                    }
                    startLocation()
                }
            }
        } catch (e: Exception) {
            LogUtils.wTag("MapLocation", e.message)
            finishCurrentLocation(Result.failure(IllegalStateException("定位服务启动失败，请检查定位权限")))
        }
    }

    /** Wait for the next live fix instead of moving to an old blue-dot position. */
    fun requestCurrentLocation(callback: (Result<LatLng>) -> Unit) {
        if (destroyed) return
        cancelCurrentLocationRequest()
        currentLocationCallback = callback
        handler.postDelayed(requestTimeout, 15000)
        startClient()
        if (currentLocationCallback == null) return
        try {
            if (map.provider == MapProvider.BAIDU) {
                baiduClient?.let { client ->
                    if (!client.isStarted) client.start()
                    // The continuous scan also delivers a fresh fix if requestLocation is throttled.
                    client.requestLocation()
                }
            } else amapClient?.startLocation()
        } catch (e: Exception) {
            finishCurrentLocation(Result.failure(IllegalStateException("无法获取当前位置，请重试")))
        }
    }

    fun cancelCurrentLocationRequest() {
        handler.removeCallbacks(requestTimeout)
        currentLocationCallback = null
    }

    fun stopFollowing() {
        followMode = FollowMode.MODE_NONE
        cancelCurrentLocationRequest()
    }

    private fun finishCurrentLocation(result: Result<LatLng>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { finishCurrentLocation(result) }
            return
        }
        if (destroyed) return
        val callback = currentLocationCallback ?: return
        cancelCurrentLocationRequest()
        callback(result)
    }

    private fun update(point: LatLng, accuracy: Float, direction: Float, live: Boolean = true,
                       fromSimulation: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { update(point, accuracy, direction, live, fromSimulation) }
            return
        }
        if (destroyed) return
        if (preferSimulation && SimulationStateStore.current.hasSession && !fromSimulation) return
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return
        val requested = currentLocationCallback != null
        map.updateLocation(point, accuracy, direction)
        if (requested) {
            if (live) {
                zoomed = true
                finishCurrentLocation(Result.success(point))
            }
            return
        }
        if (followMode == FollowMode.MODE_PERSISTENT || (followMode == FollowMode.MODE_SINGLE && !zoomed)) {
            map.animateTo(point, if (source == Source.FLOATING) 18f else 16f)
            zoomed = true
        }
    }

    fun setLocationMode(rotate: Boolean = false) = map.setLocationMode(rotate)

    fun onDestroy() {
        destroyed = true
        if (preferSimulation) {
            SimulationStateStore.state.removeObserver(simulationStateObserver)
            SimulationStateStore.location.removeObserver(simulationLocationObserver)
        }
        cancelCurrentLocationRequest()
        handler.removeCallbacksAndMessages(null)
        map.disableLocation()
        stopClients()
    }

    private fun stopClients() {
        amapClient?.stopLocation()
        amapClient?.onDestroy()
        amapClient = null
        baiduClient?.unRegisterLocationListener(baiduListener)
        baiduClient?.stop()
        baiduClient = null
    }
}
