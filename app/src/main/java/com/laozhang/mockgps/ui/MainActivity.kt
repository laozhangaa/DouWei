package com.laozhang.mockgps.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.maps.model.LatLng
import com.amap.api.services.route.DrivePathV2
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.PermissionUtils
import com.blankj.utilcode.util.ToastUtils
import com.castiel.common.base.BaseViewModel
import com.castiel.common.base.BaseActivity
import com.google.android.material.appbar.AppBarLayout
import com.laozhang.mockgps.R
import com.laozhang.mockgps.adaper.MainAdapter
import com.laozhang.mockgps.adaper.MultiplePoiAdapter
import com.laozhang.mockgps.adaper.SimpleDividerDecoration
import com.laozhang.mockgps.databinding.ActivityMainBinding
import com.laozhang.mockgps.manager.SearchManager
import com.laozhang.mockgps.manager.MapSdkCredentials
import com.laozhang.mockgps.manager.FloatingViewManger
import com.laozhang.mockgps.manager.FavoritesManager
import com.laozhang.mockgps.manager.NavigationRecoveryStore
import com.laozhang.mockgps.manager.utils.MapConvertUtils
import com.laozhang.mockgps.model.FavoriteEntry
import com.laozhang.mockgps.model.FavoriteKind
import com.laozhang.mockgps.model.FavoritePoint
import com.laozhang.mockgps.model.RouteCoordinate
import com.laozhang.mockgps.model.TravelMode
import com.laozhang.mockgps.model.NavigationOptions
import com.laozhang.mockgps.model.NavigationRecovery
import com.laozhang.mockgps.model.PlaybackPosition
import com.laozhang.mockgps.widget.FavoriteNameDialog
import com.laozhang.mockgps.widget.FavoritesDialog
import com.laozhang.mockgps.model.MockMessageModel
import com.laozhang.mockgps.model.NaviType
import com.laozhang.mockgps.model.PoiInfoModel
import com.laozhang.mockgps.model.PoiInfoType
import com.laozhang.mockgps.model.SimulationStatus
import com.laozhang.mockgps.server.GpsService
import com.laozhang.mockgps.server.SimulationStateStore
import com.laozhang.mockgps.utils.MMKVUtils
import com.laozhang.mockgps.utils.Utils
import com.laozhang.mockgps.utils.WarnDialogUtils
import com.laozhang.mockgps.widget.MapSelectDialog
import com.laozhang.mockgps.widget.NaviPopupWindow
import com.laozhang.mockgps.widget.DeleteHistoryDialog
import com.laozhang.mockgps.widget.MapServiceDialog
import com.laozhang.mockgps.widget.FavoriteCardDialog
import com.laozhang.mockgps.widget.NavigationOptionsDialog
import com.laozhang.mockgps.widget.NavigationProgressDialog
import com.laozhang.mockgps.widget.NavigationInfoDialog
import com.laozhang.mockgps.widget.DiagnosticsDialog
import kotlin.math.abs
import kotlin.math.roundToInt


/**
 * @author jiayu.liu
 */
class MainActivity : BaseActivity<ActivityMainBinding, BaseViewModel>(), View.OnClickListener {
    private var topMarginOffset: Int = 0
    private var topMargin: Int = 0
    private lateinit var adapter: MainAdapter
    private var mMapSelectDialog: MapSelectDialog? = null
    private var mapServiceDialog: MapServiceDialog? = null
    private var locationAlwaysView: View? = null
    private lateinit var poiAdapter: MultiplePoiAdapter
    private var travelMode = MMKVUtils.getTravelMode()
    private var routeRequestId: Long? = null
    private var loadedFavoriteRoute: FavoriteEntry? = null
    private var favoritesDialog: FavoritesDialog? = null
    private var favoriteNameDialog: FavoriteNameDialog? = null
    private var navigationDialog: FavoriteCardDialog? = null
    private var recoveryLoadError: String? = null

    private data class RoutePlan(
        val points: List<PoiInfoModel>, val mode: TravelMode,
        val speed: Int, val saveFavorite: Boolean, val options: NavigationOptions
    )

    private var registerForActivityResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                it.data?.run {
                    val index = getIntExtra("index", -1)
                    val parcelableExtra = this.getParcelableExtra<PoiInfoModel>("poiInfo")
                    setDataToView(index, parcelableExtra)
                }
            }
        }

    override fun initView() {
        dataBinding.title = "模拟定位"
        dataBinding.clickListener = this
        topMarginOffset = -ConvertUtils.dp2px(50f)
        topMargin = ConvertUtils.dp2px(15f)

        poiAdapter = MultiplePoiAdapter().apply {
            submitList(
                arrayListOf(
                    PoiInfoModel(),
                    PoiInfoModel(),
                )
            )

            clickListener = object : MultiplePoiAdapter.OnItemClickListener {
                override fun onItemClick(view: View, position: Int) {
                    if (!ensureMapService()) return
                    registerForActivityResult.launch(
                        Intent(
                            this@MainActivity,
                            PickMapPoiActivity::class.java
                        ).apply {
                            putExtra("from_tag", PoiInfoType.DEFAULT)
                            putExtra("index", position)
                            putExtra("model", poiAdapter.currentList()[position])
                        }
                    )
                }

                override fun onItemMove(viewHolder: RecyclerView.ViewHolder) {
                    adapter.onItemMove(viewHolder)
                }
            }
        }
        adapter = MainAdapter(poiAdapter).apply {
            setOnItemClickListener(object : MainAdapter.OnItemClickListener {
                override fun onItemClick(view: View?, model: MockMessageModel) {
                    when (model.naviType) {
                        NaviType.LOCATION -> {
                            setDataToView(model = model.locationModel)
                            with(this@MainActivity.dataBinding) {
                                appBarLayout.setExpanded(true, true)
                                recycler.scrollToPosition(0)
                            }
                        }

                        NaviType.NAVI -> {
                            val list = arrayListOf<PoiInfoModel>()
                            list.add(model.startNavi ?: PoiInfoModel())
                            model.wayNaviList?.let {
                                list.addAll(it)
                            }
                            list.add(model.endNavi ?: PoiInfoModel())
                            loadedFavoriteRoute = null
                            poiAdapter.submitList(list)
                            selectTravelMode(TravelMode.fromId(model.travelMode), model.speed)
                            model.navigationOptions?.let { MMKVUtils.setNavigationOptions(it) }
                            adapter.updateNavigationOptions()

                            with(this@MainActivity.dataBinding) {
                                appBarLayout.setExpanded(true, true)
                                recycler.scrollToPosition(0)
                            }

                        }

                        else -> {
                        }
                    }
                }

                override fun onTravelModeSelected(mode: TravelMode) {
                    selectTravelMode(mode)
                }

                override fun onItemLongClick(view: View?, model: MockMessageModel): Boolean {
                    showDeleteHistoryDialog(model)
                    return true
                }

                override fun onClick(v: View?) {
                    this@MainActivity.onClick(v)
                }

            })
        }
        poiAdapter.onPointsChanged = {
            cancelRoutePlan()
            loadedFavoriteRoute = loadedFavoriteRoute?.takeIf { entry ->
                val points = poiAdapter.currentList()
                points.all { it.latLng != null } && entry.matchesRoute(travelMode, points.map { it.favoritePoint() })
            }
        }
        dataBinding.recycler.adapter = adapter
        dataBinding.recycler.itemAnimator = null
        dataBinding.recycler.layoutManager = LinearLayoutManager(this)
        dataBinding.recycler.addItemDecoration(
            SimpleDividerDecoration(
                this,
                R.color.transparent,
                10f
            )
        )

        dataBinding.appBarLayout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            appBarLayout?.run {
                val scale = abs(verticalOffset * 1.0f / appBarLayout.totalScrollRange)
                val params =
                    dataBinding.recycler.layoutParams as ViewGroup.MarginLayoutParams
                val topMarginOffsetValue = (topMarginOffset * (1 - scale)).roundToInt()
                val topMarginValue = (topMargin * scale).roundToInt()
                params.topMargin = topMarginOffsetValue + topMarginValue
                dataBinding.recycler.layoutParams = params
            }
        })
    }

    private fun goToMockLocation(
        routeLine: DrivePathV2,
        model: MockMessageModel
    ) {
        SearchManager.INSTANCE.selectDriverLine(routeLine)
        val intent =
            Intent(this@MainActivity, MockLocationActivity::class.java)
        intent.putExtra("model", model)
        startActivity(intent)
        if (model.recoveryId == null) MMKVUtils.saveNaviData(model)
    }


    override fun initViewModel(): Class<BaseViewModel> {
        return BaseViewModel::class.java
    }

    override fun getLayout(): Int {
        return R.layout.activity_main
    }

    override fun initData() {}

    override fun initObserver() {
        SimulationStateStore.state.observe(this) {
            adapter.updateSimulationState(it)
            if (!it.hasSession) refreshRecovery()
        }
        SimulationStateStore.progress.observe(this) { adapter.updateNavigationProgress(it) }
    }

    override fun onResume() {
        super.onResume()
        adapter.updateSpeed(MMKVUtils.getSpeed().coerceIn(1, 500))
        adapter.updateNavigationOptions()
        refreshRecovery()
        //获取历史数据
        dataBinding.recycler.post { getHistoryData() }
        mMapSelectDialog?.onResume()
        //后台定位提示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (SimulationStateStore.current.hasSession &&
                !PermissionUtils.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (locationAlwaysView == null) {
                    locationAlwaysView = LayoutInflater.from(this)
                        .inflate(R.layout.layout_location_always_allow, null)
                    locationAlwaysView?.let {
                        it.findViewById<View>(R.id.btn_skip)?.setOnClickListener {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            intent.data = Uri.parse("package:$packageName")
                            try {
                                startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                ToastUtils.showShort("跳转失败，请手动开启！")
                            }
                        }

                        val frameLayout = this.window.decorView as FrameLayout
                        frameLayout.addView(
                            it,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = Gravity.BOTTOM
                                val margin = ConvertUtils.dp2px(10f)
                                setMargins(margin, 0, margin, margin * 2)
                            }
                        )
                    }
                }
            } else {
                locationAlwaysView?.let {
                    (window.decorView as FrameLayout).removeView(it)
                    locationAlwaysView = null
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelRoutePlan()
        mMapSelectDialog?.onPause()
    }

    private fun getHistoryData() {
        if (!adapter.isHeaderReady) return
        MMKVUtils.getDataList(
            if (adapter.dataBinding.includeLocationCard.llLocationCard.visibility == View.VISIBLE) MMKVUtils.LOCATION_LIST_KEY
            else MMKVUtils.MULTIPLE_NAVI_LIST_KEY
        )?.let {
            adapter.submitList(it)
        } ?: run {
            adapter.submitList(arrayListOf())
        }
    }

    private fun clearHistoryData() {
        adapter.submitList(arrayListOf())
        MMKVUtils.clearDataList(
            if (adapter.dataBinding.includeLocationCard.llLocationCard.visibility == View.VISIBLE) MMKVUtils.LOCATION_LIST_KEY
            else MMKVUtils.MULTIPLE_NAVI_LIST_KEY
        )
    }

    private fun historyKey(): String {
        return if (adapter.dataBinding.includeLocationCard.llLocationCard.visibility == View.VISIBLE)
            MMKVUtils.LOCATION_LIST_KEY else MMKVUtils.MULTIPLE_NAVI_LIST_KEY
    }

    private fun showDeleteHistoryDialog(model: MockMessageModel) {
        val title = when (model.naviType) {
            NaviType.LOCATION -> model.locationModel?.name
            else -> listOfNotNull(model.startNavi?.name, model.endNavi?.name)
                .filter { it.isNotBlank() }
                .joinToString(" → ")
        }.orEmpty().ifBlank { "这条历史记录" }
        DeleteHistoryDialog(this, title) {
                if (MMKVUtils.removeDataItem(historyKey(), model)) {
                    getHistoryData()
                    ToastUtils.showShort("已删除历史记录")
                }
            }.show()
    }

    private fun setDataToView(index: Int = -1, model: PoiInfoModel?) {
        model?.let {
            when (it.poiInfoType) {
                PoiInfoType.LOCATION -> {
                    adapter.dataBinding.includeLocationCard.tvLocationName.text =
                        it.name.orEmpty().ifBlank { "已选位置" }
                    adapter.dataBinding.includeLocationCard.tvLocationLatlng.text = String.format(
                        "经度 %.6f · 纬度 %.6f",
                        it.latLng?.longitude, it.latLng?.latitude
                    )
                    adapter.dataBinding.includeLocationCard.tvLocationLatlng.tag = it
                }

                else -> {
                    poiAdapter.submitList(
                        poiAdapter.currentList().toMutableList().apply {
                            set(index, it)
                        }
                    )
                }
            }
        }
    }

    override fun onClick(v: View?) {
        if (v?.id == R.id.btn_route_options) {
            navigationDialog?.dismiss()
            navigationDialog = NavigationOptionsDialog(this, travelMode) { options ->
                cancelRoutePlan()
                if (loadedFavoriteRoute?.routeMetadata?.preference != options.preference) loadedFavoriteRoute = null
                adapter.updateNavigationOptions()
            }.also { it.show() }
            return
        }
        if (v?.id == R.id.btn_navigation_progress || v?.id == R.id.btn_simulation_diagnostics) {
            navigationDialog?.dismiss()
            navigationDialog = if (v.id == R.id.btn_navigation_progress) NavigationProgressDialog(this) else DiagnosticsDialog(this)
            navigationDialog?.show()
            return
        }
        if (v?.id == R.id.btn_restore_navigation) { showRecovery(); return }
        cancelRoutePlan()
        if (v?.id == R.id.btn_simulation_stop) {
            controlSimulation(GpsService.ACTION_STOP)
            return
        }
        if (v?.id == R.id.btn_simulation_pause) {
            controlSimulation(if (SimulationStateStore.current.status == SimulationStatus.PAUSED)
                GpsService.ACTION_RESUME else GpsService.ACTION_PAUSE)
            return
        }
        if (v?.id == R.id.btn_start_location || v?.id == R.id.btn_start_navi) {
            when (SimulationStateStore.current.status) {
                SimulationStatus.STARTING -> return
                SimulationStatus.RUNNING -> {
                    controlSimulation(GpsService.ACTION_STOP)
                    return
                }
                SimulationStatus.PAUSED -> {
                    controlSimulation(GpsService.ACTION_RESUME)
                    return
                }
                else -> Unit
            }
        }
        if (v == dataBinding.ivExpand) {
            startActivity(Intent(this, ExpandActivity::class.java))
            return
        }
        if (!adapter.isHeaderReady) return
        when (v?.id) {
            R.id.btn_location_favorites -> { showFavorites(FavoriteKind.LOCATION); return }
            R.id.btn_route_favorites -> { showFavorites(FavoriteKind.ROUTE); return }
            R.id.btn_save_location -> { saveLocationFavorite(); return }
            R.id.btn_save_route -> { requestRoute(saveFavorite = true); return }
        }
        if ((v == adapter.dataBinding.includeLocationCard.llLocationCard ||
                    v == adapter.dataBinding.includeLocationCard.btnStartLocation ||
                    v == adapter.dataBinding.includeNaviCard.btnStartNavi) && !ensureMapService()) return
        when (v) {
            adapter.dataBinding.includeLocationCard.llLocationCard -> {
                registerForActivityResult.launch(
                    Intent(
                        this@MainActivity,
                        PickMapPoiActivity::class.java
                    ).apply {
                        putExtra("from_tag", PoiInfoType.LOCATION)
                        if (adapter.dataBinding.includeLocationCard.tvLocationLatlng.tag != null) {
                            putExtra(
                                "model",
                                adapter.dataBinding.includeLocationCard.tvLocationLatlng.tag as PoiInfoModel?
                            )
                        }
                    }
                )
            }

            adapter.dataBinding.includeLocationCard.btnStartLocation -> {
                if (adapter.dataBinding.includeLocationCard.tvLocationLatlng.tag == null) {
                    Toast.makeText(this@MainActivity, "请先点击位置卡片，在地图上选择模拟位置", Toast.LENGTH_SHORT)
                        .show()
                    return
                }
                //启动模拟导航
                Utils.checkFloatWindow(this).let {
                    if (!it) {
                        WarnDialogUtils.setFloatWindowDialog(this@MainActivity)
                        return
                    }
                    val locationModel =
                        adapter.dataBinding.includeLocationCard.tvLocationLatlng.tag as PoiInfoModel?
                    val model = MockMessageModel(
                        locationModel = locationModel,
                        naviType = NaviType.LOCATION,
                        uid = locationModel?.uid ?: ""
                    )
                    val intent = Intent(this, MockLocationActivity::class.java)
                    intent.putExtra("model", model)
                    startActivity(intent)

                    MMKVUtils.saveLocationData(model)
                }
            }

            dataBinding.ivChange -> {
                if (adapter.dataBinding.includeLocationCard.llLocationCard.visibility == View.VISIBLE) {
                    adapter.dataBinding.includeLocationCard.llLocationCard.visibility = View.GONE
                    adapter.dataBinding.includeNaviCard.llNaviCard.visibility = View.VISIBLE
                    dataBinding.title = "模拟导航"
                    dataBinding.isNavi = true
                } else {
                    adapter.dataBinding.includeLocationCard.llLocationCard.visibility = View.VISIBLE
                    adapter.dataBinding.includeNaviCard.llNaviCard.visibility = View.GONE
                    dataBinding.title = "模拟定位"
                    dataBinding.isNavi = false
                }
                dataBinding.appBarLayout.setExpanded(true, true)
                dataBinding.recycler.scrollToPosition(0)
                getHistoryData()
            }

            dataBinding.ivNaviAdd, adapter.dataBinding.includeNaviCard.btnAddWaypoint -> {
                poiAdapter.currentList().toMutableList().apply {
                    if (size >= 5) {
                        ToastUtils.showShort("最多可添加3个途经点")
                        return
                    }
                    this.add(size - 1, PoiInfoModel())
                    poiAdapter.submitList(this)
                }

            }

            adapter.dataBinding.includeNaviCard.btnStartNavi -> requestRoute(saveFavorite = false)

            adapter.dataBinding.includeNaviCard.ivNaviSetting -> {
                //导航设置
                NaviPopupWindow(this, travelMode) { adapter.updateSpeed(it) }.apply {
                    show(adapter.dataBinding.includeNaviCard.ivNaviSetting)
                }
            }

            adapter.dataBinding.tvCleanCache -> {
                if (adapter.itemCount <= 1) {
                    ToastUtils.showShort("暂无历史记录")
                } else {
                    DeleteHistoryDialog(this, "", clearAll = true) { clearHistoryData() }.show()
                }
            }

            else -> {
            }
        }
    }

    private fun selectTravelMode(mode: TravelMode, restoredSpeed: Int? = null) {
        val changed = travelMode != mode
        if (changed) {
            cancelRoutePlan()
            loadedFavoriteRoute = null
        }
        travelMode = mode
        MMKVUtils.setTravelMode(mode)
        if (restoredSpeed != null || changed) {
            MMKVUtils.setSpeed((restoredSpeed ?: mode.defaultSpeedKmH).coerceIn(1, 500))
        }
        adapter.updateTravelMode(mode)
        adapter.updateSpeed(MMKVUtils.getSpeed().coerceIn(1, 500))
    }

    private fun PoiInfoModel.favoritePoint(): FavoritePoint {
        val coordinate = requireNotNull(latLng) { "请先选择地点" }
        return FavoritePoint(name.orEmpty().ifBlank { "已选地点" }, coordinate.latitude,
            coordinate.longitude, city.orEmpty(), uid.orEmpty())
    }

    private fun cancelRoutePlan() {
        routeRequestId?.let { SearchManager.INSTANCE.cancelRoute(it) }
        routeRequestId = null
        viewModel.loading.value = false
    }

    private fun FavoritePoint.poi(type: Int = PoiInfoType.DEFAULT) = PoiInfoModel(
        LatLng(latitude, longitude), uid.ifBlank { coordinate.key() }, name, type, city)

    private fun requestRoute(saveFavorite: Boolean) {
        if (routeRequestId != null) {
            ToastUtils.showShort("路线规划中，请稍后")
            return
        }
        val points = poiAdapter.currentList().map { it.copy() }
        if (points.size !in 2..5 || points.any { it.latLng == null }) {
            ToastUtils.showShort("请先选好起点、终点和所有途经点")
            return
        }
        if (points.zipWithNext().any { (a, b) -> a.latLng == b.latLng }) {
            ToastUtils.showShort("相邻地点不能相同，请调整选点")
            return
        }
        if (!saveFavorite && !Utils.checkFloatWindow(this)) {
            WarnDialogUtils.setFloatWindowDialog(this)
            return
        }
        val plan = RoutePlan(points, travelMode, MMKVUtils.getSpeed().coerceIn(1, 500), saveFavorite, MMKVUtils.getNavigationOptions())
        val savedRoute = loadedFavoriteRoute?.takeIf {
            it.matchesRoute(plan.mode, plan.points.map { point -> point.favoritePoint() }) &&
                (plan.mode != TravelMode.DRIVING || (it.routeMetadata?.preference ?: "RECOMMENDED") == plan.options.preference)
        }
        if (savedRoute != null) {
            val path = MapConvertUtils.fromCoordinates(savedRoute.path)
            savedRoute.routeMetadata?.let { SearchManager.INSTANCE.setMetadata(path, it) }
            usePlannedRoute(path, plan)
            return
        }
        if (!ensureMapService()) return
        viewModel.loading.value = true
        routeRequestId = SearchManager.INSTANCE.routeSearch(
            points.map { it.latLng!! }, plan.mode,
            adapter.dataBinding.includeNaviCard.radioMultiRoute.isChecked, plan.options.drivingPreference
        ) { paths, error ->
            routeRequestId = null
            viewModel.loading.value = false
            if (isFinishing || isDestroyed) return@routeSearch
            if (paths.isNullOrEmpty()) {
                ToastUtils.showLong(error ?: "未找到可用道路，请调整选点后重试")
                return@routeSearch
            }
            run {
                mMapSelectDialog?.dismiss()
                mMapSelectDialog = MapSelectDialog(this, paths, points.first().latLng,
                    points.last().latLng, points.drop(1).dropLast(1).toMutableList(),
                    plan.mode.label, plan.speed,
                    if (plan.saveFavorite) "收藏此路线" else "使用此路线", plan.options
                ).apply {
                    listener = object : MapSelectDialog.MapSelectDialogListener {
                        override fun onSelectLine(routeLine: DrivePathV2) {
                            usePlannedRoute(routeLine, plan)
                        }
                    }
                    show()
                }
            }
        }
    }

    private fun usePlannedRoute(path: DrivePathV2, plan: RoutePlan) {
        if (plan.saveFavorite) {
            val entry = FavoriteEntry(
                kind = FavoriteKind.ROUTE,
                name = "${plan.points.first().name.orEmpty()} → ${plan.points.last().name.orEmpty()}".take(40),
                points = plan.points.map { it.favoritePoint() },
                travelMode = plan.mode.name, speedKmH = plan.speed,
                path = MapConvertUtils.convertLatLngList(path).map { RouteCoordinate(it.latitude, it.longitude) },
                routeMetadata = SearchManager.INSTANCE.metadataFor(path), navigationOptions = plan.options
            )
            promptSaveFavorite(entry)
        } else {
            if (!ensureMapService()) return
            goToMockLocation(path, MockMessageModel(
                startNavi = plan.points.first(), endNavi = plan.points.last(),
                wayNaviList = plan.points.drop(1).dropLast(1), naviType = NaviType.NAVI,
                speed = plan.speed, travelMode = plan.mode.name,
                navigationOptions = plan.options,
                uid = plan.mode.name + "|" + plan.points.joinToString("|") { it.favoritePoint().coordinate.key() }
            ))
        }
    }

    private fun saveLocationFavorite() {
        val point = adapter.dataBinding.includeLocationCard.tvLocationLatlng.tag as? PoiInfoModel
        if (point?.latLng == null) {
            ToastUtils.showShort("请先在地图上选择一个位置")
            return
        }
        promptSaveFavorite(FavoriteEntry(kind = FavoriteKind.LOCATION,
            name = point.name.orEmpty().ifBlank { "常用位置" }.take(40), points = listOf(point.favoritePoint())))
    }

    private fun promptSaveFavorite(entry: FavoriteEntry) {
        favoriteNameDialog?.dismiss()
        favoriteNameDialog = FavoriteNameDialog(this, "收藏${entry.kind.label}", entry.name,
            if (entry.kind == FavoriteKind.LOCATION) "常用位置单独保存，不受历史记录条数限制"
            else "${TravelMode.fromId(entry.travelMode).label} · ${entry.speedKmH} km/h · 保存完整道路轨迹"
        ) { name, done ->
            FavoritesManager.save(entry.copy(name = name)) { result ->
                done(result.exceptionOrNull()?.message ?: if (result.isFailure) "收藏保存失败，请重试" else null)
                if (result.isSuccess && !isFinishing && !isDestroyed) {
                    if (entry.kind == FavoriteKind.ROUTE && result.getOrNull()?.matchesRoute(travelMode,
                            poiAdapter.currentList().mapNotNull { if (it.latLng == null) null else it.favoritePoint() }) == true) {
                        loadedFavoriteRoute = result.getOrNull()
                    }
                    ToastUtils.showShort("已收藏${entry.kind.label}")
                }
            }
        }.also { it.show() }
    }

    private fun showFavorites(kind: FavoriteKind) {
        favoritesDialog?.dismiss()
        favoritesDialog = FavoritesDialog(this, kind) { entry ->
            if (kind == FavoriteKind.LOCATION) {
                setDataToView(model = entry.points.first().copy(name = entry.name).poi(PoiInfoType.LOCATION))
            } else {
                poiAdapter.submitList(entry.points.mapIndexed { index, point ->
                    point.poi(when (index) {
                        0 -> PoiInfoType.NAVI_START
                        entry.points.lastIndex -> PoiInfoType.NAVI_END
                        else -> PoiInfoType.DEFAULT
                    })
                })
                selectTravelMode(TravelMode.fromId(entry.travelMode), entry.speedKmH)
                entry.navigationOptions?.let { MMKVUtils.setNavigationOptions(it) }
                adapter.updateNavigationOptions()
                loadedFavoriteRoute = entry
                ToastUtils.showShort("已载入收藏道路，点击开始模拟即可使用")
            }
            dataBinding.appBarLayout.setExpanded(true, true)
            dataBinding.recycler.scrollToPosition(0)
        }.also { it.show() }
    }

    private fun controlSimulation(action: String) {
        startService(Intent(this, GpsService::class.java).setAction(action))
    }

    private fun refreshRecovery() {
        if (SimulationStateStore.current.hasSession) { adapter.updateRecoveryAvailable(false); return }
        NavigationRecoveryStore.load { result ->
            if (!isDestroyed && !isFinishing && !SimulationStateStore.current.hasSession) {
                recoveryLoadError = result.exceptionOrNull()?.message
                adapter.updateRecoveryAvailable(result.getOrNull() != null || result.isFailure)
            }
        }
    }

    private fun showRecovery() {
        if (SimulationStateStore.current.hasSession) return
        val record = NavigationRecoveryStore.cached
        navigationDialog?.dismiss()
        if (record == null) {
            navigationDialog = NavigationInfoDialog(this, "上次导航", recoveryLoadError ?: "没有可恢复的导航记录", positive = "放弃记录",
                onPositive = { dialog -> NavigationRecoveryStore.clear(); adapter.updateRecoveryAvailable(false); dialog.dismiss() }).also { it.show() }
            return
        }
        val route = record.route
        val provider = when (route.metadata.provider) { "AMAP" -> "高德"; "BAIDU" -> "百度"; else -> "已保存道路" }
        val detail = "${route.target}\n\n$provider · ${TravelMode.fromId(route.travelMode).label} · 速度${if (route.options.realistic) "上限" else ""} ${route.speedKmH} km/h\n" +
            String.format(java.util.Locale.CHINA, "上次已走 %.2f km\n", record.position.distanceMeters / 1000) +
            "继续将使用保存的道路和行驶设置；不会重新规划，也不会在后台自动启动。"
        val dialog = NavigationInfoDialog(this, "恢复上次导航", detail, positive = "继续进度", neutral = "从头开始",
            onPositive = { it.dismiss(); restoreNavigation(record) },
            onNeutral = { it.dismiss(); restoreNavigation(record.copy(position = PlaybackPosition())) })
        dialog.findViewById<android.widget.TextView>(R.id.btn_navigation_info_cancel).apply {
            text = "放弃记录"
            setOnClickListener { NavigationRecoveryStore.clear(); adapter.updateRecoveryAvailable(false); dialog.dismiss() }
        }
        navigationDialog = dialog
        dialog.show()
    }

    private fun restoreNavigation(record: NavigationRecovery) {
        if (!ensureMapService()) return
        if (!Utils.checkFloatWindow(this)) { WarnDialogUtils.setFloatWindowDialog(this); return }
        try {
            record.route.validate()
            val route = record.route
            NavigationRecoveryStore.start(route, record.position)
            val path = MapConvertUtils.fromCoordinates(route.path)
            SearchManager.INSTANCE.setMetadata(path, route.metadata)
            goToMockLocation(path, MockMessageModel(startNavi = route.points.firstOrNull()?.poi(PoiInfoType.NAVI_START),
                endNavi = route.points.lastOrNull()?.poi(PoiInfoType.NAVI_END),
                wayNaviList = route.points.drop(1).dropLast(1).map { it.poi() },
                naviType = NaviType.NAVI, speed = route.speedKmH, uid = "resume|${route.id}",
                pointType = route.metadata.coordinateSystem, travelMode = route.travelMode,
                navigationOptions = route.options, recoveryId = route.id))
        } catch (error: Exception) { ToastUtils.showLong(error.message ?: "恢复失败，请重新规划路线") }
    }

    private fun ensureMapService(): Boolean {
        if (MapSdkCredentials.isConfigured()) return true
        mapServiceDialog?.dismiss()
        mapServiceDialog = MapServiceDialog(this) {
            FloatingViewManger.INSTANCE.refreshMapProvider()
        }.also { it.show() }
        return false
    }

    override fun onDestroy() {
        navigationDialog?.dismiss()
        favoritesDialog?.dismiss()
        favoriteNameDialog?.dismiss()
        mMapSelectDialog?.dismiss()
        mapServiceDialog?.dismiss()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addCategory(Intent.CATEGORY_HOME)
            startActivity(intent)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

}
