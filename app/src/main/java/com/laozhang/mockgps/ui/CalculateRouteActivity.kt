package com.laozhang.mockgps.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import com.laozhang.mockgps.manager.AppMap
import com.amap.api.services.route.DrivePathV2
import com.blankj.utilcode.util.*
import com.castiel.common.base.BaseActivity
import com.castiel.common.base.BaseViewModel
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.ActivityCalculateRouteBinding
import com.laozhang.mockgps.manager.FollowMode
import com.laozhang.mockgps.manager.MapLocationManager
import com.laozhang.mockgps.manager.SearchManager
import com.laozhang.mockgps.manager.utils.MapConvertUtils
import com.laozhang.mockgps.manager.utils.MapDrawUtils
import com.laozhang.mockgps.model.PoiInfoModel
import com.laozhang.mockgps.model.PoiInfoType
import java.io.File
import kotlin.collections.ArrayList


/**
 * @author jiayu.liu
 */
class CalculateRouteActivity : BaseActivity<ActivityCalculateRouteBinding, BaseViewModel>(),
    View.OnClickListener {
    private var mapState: Bundle? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        mapState = savedInstanceState
        super.onCreate(savedInstanceState)
    }
    private lateinit var aMap: AppMap
    private var mapLocationManager: MapLocationManager? = null
    private val mDefaultPadding = ConvertUtils.dp2px(50f)
    private var mainIndex = 0

    /**
     * 算路成功的路线
     */
    private var routeLines: ArrayList<DrivePathV2> = arrayListOf()
    private val mSearchManagerListener  = object : SearchManager.SearchManagerListener {
        override fun onDrivingRouteResultLines(routeLines: List<DrivePathV2>?) {
            viewModel.loading.value = false
            if (routeLines?.isEmpty() != false) {
                ToastUtils.showShort("路线规划数据获取失败,请检测网络or数据是否正确!")
                return
            }
            this@CalculateRouteActivity.routeLines = ArrayList(routeLines)
            aMap.let {
                (dataBinding.tvStart.tag as PoiInfoModel?)?.latLng?.let { start ->
                    MapDrawUtils.drawMarkerToMap(it, start, "marker_start.png")
                }
                (dataBinding.tvEnd.tag as PoiInfoModel?)?.latLng?.let { end ->
                    MapDrawUtils.drawMarkerToMap(it, end, "marker_end.png")
                }

                routeLines.mapIndexed { index, line ->
                    MapDrawUtils.drawLineToMap(
                        it,
                        MapConvertUtils.convertLatLngList(line),
                        Rect(
                            mDefaultPadding,
                            mDefaultPadding + dataBinding.clPanel.height,
                            mDefaultPadding,
                            mDefaultPadding
                        ),
                        index == mainIndex
                    )
                }
            }
            dataBinding.fileName = "${dataBinding.tvStart.text}-${dataBinding.tvEnd.text}"
        }
    }

    private var registerForActivityResultToStartPoi =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                it.data?.run {
                    setDataToView(getParcelableExtra<PoiInfoModel>("poiInfo"), dataBinding.tvStart)
                }
            }
        }


    private var registerForActivityResultToEndPoi =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                it.data?.run {
                    setDataToView(getParcelableExtra<PoiInfoModel>("poiInfo"), dataBinding.tvEnd)
                }
            }
        }

    override fun initViewModel(): Class<BaseViewModel> {
        return BaseViewModel::class.java
    }

    override fun getLayout(): Int {
        return R.layout.activity_calculate_route
    }

    override fun initView() {
        initMap()

        System.currentTimeMillis()

        ClickUtils.applySingleDebouncing(dataBinding.tvStart, this)
        ClickUtils.applySingleDebouncing(dataBinding.tvEnd, this)
        ClickUtils.applySingleDebouncing(dataBinding.btnChange, this)
        ClickUtils.applySingleDebouncing(dataBinding.btnStartRoute, this)
        ClickUtils.applySingleDebouncing(dataBinding.btnSaveFile, this)
    }


    private fun initMap() {

        dataBinding.mapview.onCreate(mapState)
        aMap = dataBinding.mapview.map

        aMap.setZoom(16f)

        mapLocationManager = MapLocationManager(this, aMap, FollowMode.MODE_SINGLE)
    }


    override fun initData() {

    }


    override fun initObserver() {
    }

    override fun onClick(v: View?) {
        when (v) {
            dataBinding.tvStart -> {
                registerForActivityResultToStartPoi.launch(Intent(
                    this@CalculateRouteActivity,
                    PickMapPoiActivity::class.java
                ).apply {
                    putExtra("from_tag", PoiInfoType.DEFAULT)
                    if (dataBinding.tvStart.tag != null) {
                        putExtra("model", dataBinding.tvStart.tag as PoiInfoModel?)
                    }
                })
            }

            dataBinding.tvEnd -> {
                registerForActivityResultToEndPoi.launch(Intent(
                    this@CalculateRouteActivity,
                    PickMapPoiActivity::class.java
                ).apply {
                    putExtra("from_tag", PoiInfoType.DEFAULT)
                    if (dataBinding.tvEnd.tag != null) {
                        putExtra("model", dataBinding.tvEnd.tag as PoiInfoModel?)
                    }
                })
            }

            dataBinding.btnStartRoute -> {
                val start = (dataBinding.tvStart.tag as PoiInfoModel?)?.latLng
                val end = (dataBinding.tvEnd.tag as PoiInfoModel?)?.latLng
                if (start == null || end == null) {
                    ToastUtils.showShort("起终点不能为空")
                    return
                }
                mainIndex = 0
                aMap.clear()
                routeLines.clear()
                viewModel.loading.value = true
                SearchManager.INSTANCE.driverSearch(start, end, true)
            }

            dataBinding.btnChange -> {
                if (routeLines.isEmpty()) {
                    ToastUtils.showShort("没有路线切换")
                    return
                }
                aMap.clear()

                mainIndex = ++mainIndex % routeLines.size
                aMap.let {
                    routeLines.mapIndexed { index, line ->
                        MapDrawUtils.drawLineToMap(
                            it,
                            MapConvertUtils.convertLatLngList(line),
                            Rect(
                                mDefaultPadding,
                                mDefaultPadding + dataBinding.clPanel.height,
                                mDefaultPadding,
                                mDefaultPadding
                            ),
                            index == mainIndex
                        )
                    }
                }
                dataBinding.fileName = "${dataBinding.tvStart.text}-${dataBinding.tvEnd.text}"
            }

            dataBinding.btnSaveFile -> {
                if (routeLines.isEmpty() || mainIndex < 0 || mainIndex >= routeLines.size) {
                    ToastUtils.showShort("数据列表为null！，无法保存")
                    return
                }
                val convertLatLngList = MapConvertUtils.convertLatLngList(routeLines[mainIndex])
                val builder = StringBuilder()
                convertLatLngList.map {
                    builder.append(it.longitude).append(",").append(it.latitude).append(";")
                }
                if (!TextUtils.isEmpty(builder)) {
                    val file = File(getExternalFilesDir("nav_path"), "${dataBinding.fileName}.txt")
                    if (FileUtils.isFileExists(file)) {
                        ToastUtils.showShort("文件已经存在！请重命名文件名称")
                        return
                    }
                    FileIOUtils.writeFileFromString(
                        file,
                        builder.toString()
                    ).let {
                        ToastUtils.showShort(if (it) "保存成功" else "保存失败")
                        if (it) {
                            finish()
                        }
                    }
                }
            }

            else -> {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SearchManager.INSTANCE.addSearchManagerListener(mSearchManagerListener)
        dataBinding.mapview.onResume()
    }

    override fun onPause() {
        super.onPause()
        SearchManager.INSTANCE.removeSearchManagerListener(mSearchManagerListener)
        dataBinding.mapview.onPause()

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        dataBinding.mapview.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapLocationManager?.onDestroy()
        dataBinding.mapview.onDestroy()
        super.onDestroy()
    }




    private fun setDataToView(model: PoiInfoModel?, view: AppCompatTextView) {
        model?.run {
            when (poiInfoType) {
                PoiInfoType.DEFAULT -> {
                    view.text = String.format(
                        "%s",
                        name
                    )
                    view.tag = this
                }

                else -> {
                }
            }
        }
    }
}