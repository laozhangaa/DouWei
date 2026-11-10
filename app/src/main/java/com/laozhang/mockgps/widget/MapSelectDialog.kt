package com.laozhang.mockgps.widget

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.laozhang.mockgps.manager.AppMap
import com.laozhang.mockgps.manager.AppPolyline
import com.amap.api.maps.model.LatLng
import com.amap.api.services.route.DrivePathV2
import com.blankj.utilcode.util.ClickUtils
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.castiel.common.dialog.BaseDialog
import com.laozhang.mockgps.R
import com.laozhang.mockgps.manager.utils.MapConvertUtils
import com.laozhang.mockgps.manager.utils.MapDrawUtils
import com.laozhang.mockgps.manager.SearchManager
import com.laozhang.mockgps.model.DrivingPreference
import com.laozhang.mockgps.model.NavigationOptions
import com.laozhang.mockgps.model.PoiInfoModel
import kotlinx.android.synthetic.main.dialog_select_navi_map.*

/**
 * @author jiayu.liu
 */
class MapSelectDialog(
    context: Context,
    private var routeLines: List<DrivePathV2>,
    private var start: LatLng?,
    private var end: LatLng?,
    wayList: MutableList<PoiInfoModel>?,
    private val travelLabel: String = "驾车",
    private val speedKmH: Int = 60,
    selectLabel: String = "选择路线",
    private val options: NavigationOptions = NavigationOptions()
) : BaseDialog(context) {

    private val mHorizontalPadding = ConvertUtils.dp2px(20f)
    private val mMapPadding = ConvertUtils.dp2px(30f)
    private val screenWidth = ScreenUtils.getScreenWidth()
    private val mOverlayList = ArrayList<AppPolyline>()
    private var mainIndex = 0
    var listener: MapSelectDialogListener? = null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        layoutInflater.inflate(R.layout.dialog_select_navi_map, null, false).apply {
            setContentView(this)
        }

        texture_mapview.onCreate(null)
        btn_select.text = selectLabel
        btn_change.visibility = if (routeLines.size > 1) View.VISIBLE else View.GONE
        texture_mapview.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitSelectedRoute()
        }
        texture_mapview.map.let {
            it.setGesturesEnabled(false)
            //渲染路线
            it.setOnMapLoadedListener {
                start?.let { start ->
                    MapDrawUtils.drawMarkerToMap(it, start, "marker_start.png")
                }
                wayList?.map { model ->
                    model.latLng?.let { latLng ->
                        MapDrawUtils.drawMarkerToMap(it, latLng, "marker_way.png")
                    }
                }
                end?.let { end ->
                    MapDrawUtils.drawMarkerToMap(it, end, "marker_end.png")
                }
                drawLine(it)
            }
        }
        ClickUtils.applySingleDebouncing(btn_change) {
            mainIndex = ++mainIndex % routeLines.size
            texture_mapview.map.let {
                drawLine(it)
            }
        }
        ClickUtils.applySingleDebouncing(btn_select) {
            listener?.onSelectLine(routeLines[mainIndex])
            dismiss()
        }

        window?.let {
            val lp: WindowManager.LayoutParams = it.attributes
            lp.width = screenWidth - mHorizontalPadding
            lp.height = minOf(screenWidth + ConvertUtils.dp2px(60f), ScreenUtils.getScreenHeight() - ConvertUtils.dp2px(64f))
            lp.gravity = Gravity.CENTER
            lp.dimAmount = 0.5f
            it.attributes = lp
        }

    }

    override fun show() {
        super.show()
        texture_mapview.onResume()
    }

    private fun drawLine(map: AppMap) {
        val line = routeLines[mainIndex]
        val kilometers = line.distance / 1000.0
        val metadata = SearchManager.INSTANCE.metadataFor(line)
        val provider = when (metadata.provider) { "AMAP" -> "高德"; "BAIDU" -> "百度"; else -> "已保存路线" }
        val minutes = if (speedKmH > 0) kotlin.math.ceil(kilometers / speedKmH * 60).toInt().coerceAtLeast(1) else 0
        tv_route_summary.text = buildString {
            append(String.format(java.util.Locale.CHINA, "%s · %s · 方案 %d/%d · %.2f km", provider, travelLabel, mainIndex + 1, routeLines.size, kilometers))
            if (travelLabel == "驾车") append("\n${DrivingPreference.fromId(metadata.preference).label}")
            if (metadata.sdkDurationSeconds > 0) append(" · 地图预计 ${kotlin.math.ceil(metadata.sdkDurationSeconds / 60.0).toInt()} 分钟")
            metadata.trafficLightCount?.let { append("\n红绿灯 $it 个") }
            metadata.tollYuan?.let { append(String.format(java.util.Locale.CHINA, " · 预计收费 ¥%.0f", it)) }
            if (metadata.roadNames.isNotEmpty()) append("\n途经 " + metadata.roadNames.take(3).joinToString(" / ").take(54))
            append("\n${if (options.realistic) "速度上限" else "匀速"} $speedKmH km/h · ${if (options.realistic) "行驶时间会随减速增加" else "模拟约 $minutes 分钟"}")
            if (options.realistic && options.simulateStops && travelLabel == "驾车")
                append("\n模拟停车 ${metadata.estimatedStopMeters.size} 处 · 每处 ${options.stopSeconds} 秒（路段估算）")
        }
        mOverlayList.map {
            it.remove()
        }.also {
            mOverlayList.clear()
        }

        routeLines.mapIndexed { index, route ->
            MapDrawUtils.drawLineToMap(
                map,
                MapConvertUtils.convertLatLngList(route),
                Rect(mMapPadding, mMapPadding, mMapPadding, mMapPadding),
                index == mainIndex, animateMapStatus = false
            )?.let { overlay ->
                mOverlayList.add(overlay)
            }
        }
        fitSelectedRoute()
    }

    private fun fitSelectedRoute() {
        texture_mapview.post {
            if (!mapDestroyed && texture_mapview.width > 0 && texture_mapview.height > 0) {
                texture_mapview.map.fitPoints(MapConvertUtils.convertLatLngList(routeLines[mainIndex]),
                    Rect(mMapPadding, mMapPadding, mMapPadding, mMapPadding))
            }
        }
    }

    public fun onResume() {
        if (!mapDestroyed) texture_mapview.onResume()
    }

    public fun onPause() {
        if (!mapDestroyed) texture_mapview.onPause()
    }

    private var mapDestroyed = false

    override fun dismiss() {
        if (!mapDestroyed) {
            texture_mapview.onPause()
            texture_mapview.onDestroy()
            mapDestroyed = true
        }
        super.dismiss()
    }

    interface MapSelectDialogListener {
        fun onSelectLine(routeLine: DrivePathV2)
    }
}
