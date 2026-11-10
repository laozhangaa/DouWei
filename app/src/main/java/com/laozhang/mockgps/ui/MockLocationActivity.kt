package com.laozhang.mockgps.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.laozhang.mockgps.manager.AppMap
import com.blankj.utilcode.util.ClickUtils
import com.blankj.utilcode.util.ConvertUtils
import com.castiel.common.base.BaseActivity
import com.castiel.common.base.BaseViewModel
import com.laozhang.mockgps.R
import com.laozhang.mockgps.databinding.ActivityNaviBinding
import com.laozhang.mockgps.manager.FollowMode
import com.laozhang.mockgps.manager.MapLocationManager
import com.laozhang.mockgps.manager.utils.MapDrawUtils
import com.laozhang.mockgps.manager.SearchManager
import com.laozhang.mockgps.model.MockMessageModel
import com.laozhang.mockgps.model.NaviType
import com.laozhang.mockgps.server.GpsService
import com.laozhang.mockgps.utils.Utils
import kotlinx.android.synthetic.main.activity_navi.*


/**
 * @author jiayu.liu
 */
class MockLocationActivity : BaseActivity<ActivityNaviBinding, BaseViewModel>(),
    View.OnClickListener {
    private var mapState: Bundle? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        mapState = savedInstanceState
        super.onCreate(savedInstanceState)
    }
    private lateinit var aMap: AppMap
    private var mNaviType: Int = NaviType.LOCATION
    private val mPadding: Int = ConvertUtils.dp2px(50f)
    private var mapLocationManager: MapLocationManager? = null
    private var mockSettingsDialog: AlertDialog? = null
    private var waitingForMockSettings = false
    private var started = false
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) startMock()
        else {
            Toast.makeText(this, "需要定位权限才能启动模拟", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun hasLocationPermission() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun initViewModel(): Class<BaseViewModel> {
        return BaseViewModel::class.java
    }

    override fun getLayout(): Int {
        return R.layout.activity_navi
    }

    override fun initView() {
        ClickUtils.applySingleDebouncing(iv_back, this)

        mapview.onCreate(mapState)
        aMap = mapview.map

        aMap.setOnMapLoadedListener {
            startMock()
        }
    }

    override fun initData() {}

    private fun startMock() {
        if (started || isFinishing) return
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (!Utils.isAllowMockLocation(this)) {
            if (mockSettingsDialog?.isShowing == true) return
            mockSettingsDialog = AlertDialog.Builder(this)
                .setTitle("设置模拟定位")
                .setMessage("请在开发者选项的“模拟位置信息应用”中选择豆位，再启动模拟。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        waitingForMockSettings = true
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (e: Exception) {
                        waitingForMockSettings = false
                        Toast.makeText(this, "请手动打开系统开发者选项设置", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                .setNegativeButton("取消") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        val model = intent.getParcelableExtra<MockMessageModel>("model")
        if (model == null) {
            pickPoiError()
            return
        }
        with(model) {
            started = true
            this@MockLocationActivity.mNaviType = naviType
            //开启定位小蓝点展示
            mapLocationManager = MapLocationManager(
                this@MockLocationActivity,
                aMap,
                if (mNaviType == NaviType.LOCATION) FollowMode.MODE_PERSISTENT else FollowMode.MODE_NONE,
                preferSimulation = true
            )
            when (naviType) {
                NaviType.LOCATION -> {
                    locationModel?.run {
                        startMockServer(model)
                    } ?: {
                        pickPoiError()
                    }
                }

                NaviType.NAVI, NaviType.NAVI_FILE -> {
                    SearchManager.INSTANCE.polylineList.let {
                        if (it.isEmpty()) {
                            pickPoiError()
                            return
                        }
                        aMap.clear()
                        startNavi?.latLng?.let { start ->
                            MapDrawUtils.drawMarkerToMap(aMap, start, "marker_start.png")
                        } ?: run {
                            MapDrawUtils.drawMarkerToMap(aMap, it[0], "marker_start.png")
                        }
                        wayNaviList?.map {
                            it.latLng?.let { latLng ->
                                MapDrawUtils.drawMarkerToMap(aMap, latLng, "marker_way.png")
                            }
                        }
                        endNavi?.latLng?.let { end ->
                            MapDrawUtils.drawMarkerToMap(aMap, end, "marker_end.png")
                        } ?: run {
                            MapDrawUtils.drawMarkerToMap(
                                aMap,
                                it[it.size - 1],
                                "marker_end.png"
                            )
                        }
                        MapDrawUtils.drawLineToMap(
                            aMap,
                            it,
                            Rect(mPadding, mPadding, mPadding, mPadding)
                        )
                        startMockServer(model)
                    }
                }

                else -> {
                }
            }
        }
    }

    override fun initObserver() {

    }

    private fun pickPoiError() {
        Toast.makeText(this, "选址数据异常，请重新选择地址再重试", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startMockServer(parcelable: Parcelable?) {
        // GpsService validates the real provider write and reports failures to the home screen.
        //启动服务  定位以及悬浮窗
        GpsService.launch(this, Intent(this, GpsService::class.java).apply {
            parcelable?.let {
                putExtras(
                    Bundle().apply {
                        putParcelable("info", it)
                    })
            }
        })
    }

    override fun onResume() {
        super.onResume()
        mapview.onResume()
        if (waitingForMockSettings) {
            waitingForMockSettings = false
            if (Utils.isAllowMockLocation(this)) startMock()
            else finish()
        }
    }

    override fun onPause() {
        super.onPause()
        mapview.onPause()

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapview.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mockSettingsDialog?.dismiss()
        mapLocationManager?.onDestroy()
        mapview.onDestroy()
        super.onDestroy()
    }



    override fun onClick(v: View?) {
        when (v) {
            iv_back -> {
                finish()
            }

            else -> {
            }
        }
    }
}
