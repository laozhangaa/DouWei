package com.laozhang.mockgps.manager

import android.os.Handler
import android.os.Looper
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.*
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.route.*
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.Utils
import com.laozhang.mockgps.manager.utils.MapConvertUtils
import com.laozhang.mockgps.model.TravelMode
import com.laozhang.mockgps.model.DrivingPreference
import com.laozhang.mockgps.model.RouteMetadata
import com.laozhang.mockgps.manager.utils.RouteMetadataFactory
import com.laozhang.mockgps.utils.MMKVUtils
import java.util.WeakHashMap

/** Each request owns its SDK listener. Cancelled/expired callbacks cannot start another route. */
class SearchManager private constructor() {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<SearchManagerListener>()
    private val requests = mutableMapOf<Long, Request>()
    private var sequence = 0L
    private var legacyRequest: Long? = null
    var polylineList: ArrayList<LatLng> = arrayListOf()
    var selectedMetadata = RouteMetadata()
    private val metadata = WeakHashMap<DrivePathV2, RouteMetadata>()
    fun metadataFor(path: DrivePathV2) = metadata[path] ?: RouteMetadata()
    fun setMetadata(path: DrivePathV2, details: RouteMetadata) { details.validate(); metadata[path] = details }

    private class Request(
        val id: Long, val provider: MapProvider, val mode: TravelMode,
        val points: List<LatLng>, val multiple: Boolean, val preference: DrivingPreference,
        val callback: (List<DrivePathV2>?, String?) -> Unit
    ) {
        var segment = 0
        val completed = mutableListOf<DrivePathV2>()
        var baidu: RoutePlanSearch? = null
        var amap: RouteSearchV2? = null
        var timeout: Runnable? = null
    }

    companion object { val INSTANCE: SearchManager by lazy { SearchManager() } }
    fun addSearchManagerListener(listener: SearchManagerListener) { listeners.add(listener) }
    fun removeSearchManagerListener(listener: SearchManagerListener) { listeners.remove(listener) }

    /** Compatibility entry point for file generation and external driving requests. */
    fun driverSearch(startLatLng: LatLng?, endLatLng: LatLng?, multiRoute: Boolean,
                     wayList: MutableList<LatLng>? = null) {
        legacyRequest?.let { cancelRoute(it) }
        val points = if (startLatLng != null && endLatLng != null)
            listOf(startLatLng) + wayList.orEmpty() + endLatLng else emptyList()
        legacyRequest = routeSearch(points, TravelMode.DRIVING, multiRoute) { paths, error ->
            legacyRequest = null
            if (error != null) ToastUtils.showShort(error)
            listeners.toList().forEach { it.onDrivingRouteResultLines(paths) }
        }
    }

    fun routeSearch(points: List<LatLng>, mode: TravelMode, multiple: Boolean,
                    preference: DrivingPreference = MMKVUtils.getNavigationOptions().drivingPreference,
                    callback: (List<DrivePathV2>?, String?) -> Unit): Long {
        val request = Request(++sequence, MapProvider.current, mode, points.toList(), multiple, preference, callback)
        requests[request.id] = request
        // Always return the id before invoking a callback, including invalid input.
        main.post {
            if (requests[request.id] !== request) return@post
            try {
                require(points.size in 2..5) { "请设置起终点，最多添加 3 个途经点" }
                require(mode != TravelMode.DRIVING || DrivingPolicyMapper.supports(request.provider, preference)) {
                    "百度当前不支持${preference.label}，请在“路线与行驶”中调整或切换高德"
                }
                check(MapSdkCredentials.initialize(request.provider)) { "请先在设置中配置地图服务凭证" }
                createSearch(request)
                submitSegment(request)
            } catch (error: Exception) { fail(request, error.message ?: "路线请求失败") }
        }
        return request.id
    }

    fun cancelRoute(id: Long) {
        requests.remove(id)?.let { release(it) }
    }

    private fun createSearch(r: Request) {
        if (r.provider == MapProvider.BAIDU) {
            r.baidu = RoutePlanSearch.newInstance().apply {
                setOnGetRoutePlanResultListener(object : OnGetRoutePlanResultListener {
                    override fun onGetDrivingRouteResult(result: DrivingRouteResult?) = receive(r,
                        result?.error == SearchResult.ERRORNO.NO_ERROR,
                        result?.routeLines?.map { MapConvertUtils.fromBaiduRoute(it) }, result?.error.toString())
                    override fun onGetWalkingRouteResult(result: WalkingRouteResult?) = receive(r,
                        result?.error == SearchResult.ERRORNO.NO_ERROR,
                        result?.routeLines?.map { MapConvertUtils.fromBaiduWalk(it) }, result?.error.toString())
                    override fun onGetBikingRouteResult(result: BikingRouteResult?) = receive(r,
                        result?.error == SearchResult.ERRORNO.NO_ERROR,
                        result?.routeLines?.map { MapConvertUtils.fromBaiduRide(it) }, result?.error.toString())
                    override fun onGetTransitRouteResult(result: TransitRouteResult?) {}
                    override fun onGetMassTransitRouteResult(result: MassTransitRouteResult?) {}
                    override fun onGetIndoorRouteResult(result: IndoorRouteResult?) {}
                    override fun onGetIntegralRouteResult(result: IntegralRouteResult?) {}
                })
            }
        } else {
            r.amap = RouteSearchV2(Utils.getApp()).apply {
                setRouteSearchListener(object : RouteSearchV2.OnRouteSearchListener {
                    override fun onDriveRouteSearched(result: DriveRouteResultV2?, code: Int) =
                        receive(r, code == AMapException.CODE_AMAP_SUCCESS, result?.paths, code.toString())
                    override fun onWalkRouteSearched(result: WalkRouteResultV2?, code: Int) =
                        receive(r, code == AMapException.CODE_AMAP_SUCCESS,
                            result?.paths?.map { MapConvertUtils.fromAmapWalk(it) }, code.toString())
                    override fun onRideRouteSearched(result: RideRouteResultV2?, code: Int) =
                        receive(r, code == AMapException.CODE_AMAP_SUCCESS,
                            result?.paths?.map { MapConvertUtils.fromAmapRide(it) }, code.toString())
                    override fun onBusRouteSearched(result: BusRouteResultV2?, code: Int) {}
                })
            }
        }
    }

    private fun submitSegment(r: Request) {
        val driving = r.mode == TravelMode.DRIVING
        val start = if (driving) r.points.first() else r.points[r.segment]
        val end = if (driving) r.points.last() else r.points[r.segment + 1]
        r.timeout?.let { main.removeCallbacks(it) }
        r.timeout = Runnable { fail(r, "路线规划超时，请检查网络后重试") }.also {
            main.postDelayed(it, 45_000L)
        }
        if (r.provider == MapProvider.BAIDU) {
            val from = PlanNode.withLocation(start.toBaiduPoint())
            val to = PlanNode.withLocation(end.toBaiduPoint())
            val accepted = when {
                driving -> r.baidu!!.drivingSearch(DrivingRoutePlanOption().from(from).to(to).apply {
                    policy(DrivingPolicyMapper.baidu(r.preference))
                    trafficPolicy(DrivingRoutePlanOption.DrivingTrafficPolicy.ROUTE_PATH_AND_TRAFFIC)
                    if (r.points.size > 2) passBy(r.points.drop(1).dropLast(1).map {
                        PlanNode.withLocation(it.toBaiduPoint())
                    })
                })
                r.mode.usesWalkingRoads -> r.baidu!!.walkingSearch(WalkingRoutePlanOption().from(from).to(to))
                else -> r.baidu!!.bikingSearch(BikingRoutePlanOption().from(from).to(to).ridingType(0))
            }
            check(accepted) { "百度路线请求未能提交" }
        } else {
            val fromTo = RouteSearchV2.FromAndTo(start.toPoint(), end.toPoint())
            when {
                driving -> r.amap!!.calculateDriveRouteAsyn(RouteSearchV2.DriveRouteQuery(fromTo,
                    DrivingPolicyMapper.amap(r.preference),
                    r.points.drop(1).dropLast(1).map { it.toPoint() }, null, null).apply {
                    showFields = RouteSearchV2.ShowFields.POLINE or RouteSearchV2.ShowFields.COST
                })
                r.mode.usesWalkingRoads -> r.amap!!.calculateWalkRouteAsyn(RouteSearchV2.WalkRouteQuery(fromTo).apply {
                    showFields = RouteSearchV2.ShowFields.POLINE
                })
                else -> r.amap!!.calculateRideRouteAsyn(RouteSearchV2.RideRouteQuery(fromTo).apply {
                    showFields = RouteSearchV2.ShowFields.POLINE
                })
            }
        }
    }

    private fun receive(r: Request, success: Boolean, paths: List<DrivePathV2>?, error: String) {
        main.post {
            if (requests[r.id] !== r) return@post
            if (!success) {
                fail(r, "${r.provider.label}${r.mode.label}规划失败（$error），请检查服务权限、网络或调整选点")
                return@post
            }
            val usable = paths.orEmpty().filter { MapConvertUtils.hasRoadGeometry(it) }
            if (usable.isEmpty()) {
                fail(r, "未找到可用的${r.mode.label}道路，请调整起终点或途经点")
                return@post
            }
            try {
                if (r.mode == TravelMode.DRIVING || r.points.size == 2) {
                    finish(r, if (r.multiple) usable else usable.take(1))
                } else {
                    r.completed.add(usable.first())
                    r.segment++
                    if (r.segment < r.points.size - 1) submitSegment(r)
                    else finish(r, listOf(MapConvertUtils.mergeRoadSegments(r.completed)))
                }
            } catch (error: Exception) { fail(r, error.message ?: "路线合并失败，请重新规划") }
        }
    }

    private fun fail(r: Request, message: String) = finish(r, null, message)
    private fun finish(r: Request, paths: List<DrivePathV2>?, error: String? = null) {
        if (requests.remove(r.id) !== r) return
        release(r)
        paths?.forEach { path ->
            metadata[path] = RouteMetadataFactory.fromPath(path, r.provider.name,
                if (r.mode == TravelMode.DRIVING) r.preference else DrivingPreference.RECOMMENDED)
        }
        r.callback(paths, error)
    }
    private fun release(r: Request) {
        r.timeout?.let { main.removeCallbacks(it) }
        r.baidu?.destroy()
        r.amap?.setRouteSearchListener(null)
        r.baidu = null
        r.amap = null
    }

    fun selectDriverLine(routeLine: DrivePathV2?) {
        selectedMetadata = routeLine?.let { metadataFor(it) } ?: RouteMetadata()
        polylineList.clear()
        polylineList.addAll(MapConvertUtils.convertLatLngList(routeLine))
    }
    private fun LatLng.toPoint() = LatLonPoint(latitude, longitude)
    interface SearchManagerListener { fun onDrivingRouteResultLines(routeLines: List<DrivePathV2>?) }
}
