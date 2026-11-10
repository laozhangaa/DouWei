package com.laozhang.mockgps.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.laozhang.mockgps.manager.FloatingViewManger
import com.laozhang.mockgps.manager.PlaceSearch
import com.laozhang.mockgps.manager.PlaceSuggestion
import com.laozhang.mockgps.model.ProvinceCityData
import com.laozhang.mockgps.widget.ProvinceCityPicker
import com.laozhang.mockgps.manager.AppMap
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.blankj.utilcode.util.KeyboardUtils
import com.blankj.utilcode.util.ToastUtils
import com.castiel.common.base.BaseActivity
import com.castiel.common.base.BaseViewModel
import com.laozhang.mockgps.R
import com.laozhang.mockgps.adaper.PoiListAdapter
import com.laozhang.mockgps.adaper.SimpleDividerDecoration
import com.laozhang.mockgps.databinding.ActivityPickBinding
import com.laozhang.mockgps.manager.FollowMode
import com.laozhang.mockgps.manager.MapLocationManager
import com.laozhang.mockgps.model.PoiInfoModel
import com.laozhang.mockgps.model.PoiInfoType
import com.laozhang.mockgps.widget.InputLatLngDialog
import kotlinx.android.synthetic.main.activity_pick.*
import java.util.Locale
import kotlin.math.abs

class PickMapPoiActivity : BaseActivity<ActivityPickBinding, BaseViewModel>(), View.OnClickListener {
    private lateinit var aMap: AppMap
    private val handler = Handler(Looper.getMainLooper())
    private val poiListAdapter = PoiListAdapter()
    private var selectedPoi: PoiInfoModel? = null
    private var selectedSearchPoint: LatLng? = null
    private var mapLocationManager: MapLocationManager? = null
    private lateinit var placeSearch: PlaceSearch
    private var cityPicker: ProvinceCityPicker? = null
    private var isLocating = false
    private var locationRequestVersion = 0
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) locateCurrentPosition()
        else showLocationError("需要定位权限，请在系统设置中允许豆位获取位置")
    }
    private var selectedSearchCity: ProvinceCityData.City? = null
    private var citySelectionMade = false
    private var detectedCity = ""
    private var inputDialog: InputLatLngDialog? = null
    private var index = -1
    private var geocodeVersion = 0
    private var searchVersion = 0
    private var mapState: Bundle? = null
    @PoiInfoType private var poiInfoType = PoiInfoType.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        mapState = savedInstanceState
        super.onCreate(savedInstanceState)
    }
    override fun initViewModel() = BaseViewModel::class.java
    override fun getLayout() = R.layout.activity_pick
    override fun initData() { index = intent.getIntExtra("index", -1) }
    override fun initObserver() {}

    override fun initView() {
        dataBinding.clicklistener = this
        dataBinding.isShowSearch = false
        poiInfoType = intent.getIntExtra("from_tag", PoiInfoType.DEFAULT)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = poiListAdapter
        recycler.addItemDecoration(SimpleDividerDecoration(this))
        recycler.itemAnimator = null
        mapview.onCreate(mapState)
        aMap = mapview.map
        placeSearch = PlaceSearch(this, mapview.provider)
        citySelectionMade = mapState?.getBoolean("city_selection_made") ?: false
        mapState?.getString("search_city_code")?.let { code ->
            selectedSearchCity = ProvinceCityData.City(code, mapState?.getString("search_city_name").orEmpty())
        }
        updateSearchCityLabel()
        cityPicker = ProvinceCityPicker(this,
            onVisibilityChanged = { FloatingViewManger.INSTANCE.setTemporarilyHidden(it) }) { city ->
            citySelectionMade = true
            selectedSearchCity = city
            updateSearchCityLabel()
            scheduleSuggestions()
        }
        aMap.setOnMapTouchListener { event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                selectedSearchPoint = null
                cancelLocationRequest()
                mapLocationManager?.stopFollowing()
                editViewShow(false)
            }
        }

        poiListAdapter.setOnItemClickListener(object : PoiListAdapter.OnItemClickListener {
            override fun onItemClick(poiInfo: PlaceSuggestion) {
                cancelLocationRequest()
                mapLocationManager?.stopFollowing()
                val point = poiInfo.point
                val location = LatLng(point.latitude, point.longitude)
                geocodeVersion++
                selectedSearchPoint = location
                updatePoi(location, poiInfo.name, poiInfo.city, poiInfo.uid)
                editViewShow(false)
                changeCenterLatLng(location)
            }
        })
        val searchWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { scheduleSuggestions() }
        }
        et_search.addTextChangedListener(searchWatcher)
        et_search.setOnFocusChangeListener { _, focused ->
            if (focused) editViewShow(true)
        }
        et_search.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                scheduleSuggestions()
                KeyboardUtils.hideSoftInput(et_search)
                true
            } else false
        }

        val initial = intent.getParcelableExtra<PoiInfoModel>("model")
        initial?.latLng?.let {
            selectedSearchPoint = it
            updatePoi(it, initial.name, initial.city, initial.uid)
            changeCenterLatLng(it)
        }
        aMap.setOnCameraChangeListener(object : AppMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) {
                geocodeVersion++
                val target = position?.target ?: return
                if (selectedSearchPoint == null && !samePoint(target, selectedPoi?.latLng)) {
                    updatePoi(target, coordinateName(target), null)
                }
            }
            override fun onCameraChangeFinish(position: CameraPosition?) {
                val target = position?.target ?: return
                if (samePoint(target, selectedSearchPoint)) return
                selectedSearchPoint = null
                val version = ++geocodeVersion
                handler.postDelayed({
                    if (version == geocodeVersion && !isDestroyed) reverseGeocode(target, version)
                }, 250)
            }
        })
        mapLocationManager = MapLocationManager(this, aMap,
            if (initial?.latLng == null) FollowMode.MODE_SINGLE else FollowMode.MODE_NONE)
    }

    private fun updateSearchCityLabel() {
        dataBinding.city = if (citySelectionMade) selectedSearchCity?.name ?: "全国"
            else detectedCity.ifBlank { "全国" }
    }

    private fun scheduleSuggestions() {
        val version = ++searchVersion
        val keyword = et_search.text.toString().trim()
        val city = if (citySelectionMade) selectedSearchCity?.name.orEmpty() else detectedCity
        val cityCode = selectedSearchCity?.code
        if (keyword.isEmpty()) {
            poiListAdapter.setData(null)
            recycler.visibility = View.GONE
            return
        }
        handler.postDelayed({
            if (version != searchVersion || isDestroyed || dataBinding.isShowSearch != true) return@postDelayed
            placeSearch.suggest(keyword, city, cityCode) { result ->
                if (isDestroyed || version != searchVersion || dataBinding.isShowSearch != true) return@suggest
                result.onSuccess { tips ->
                    poiListAdapter.setData(tips)
                    recycler.visibility = View.VISIBLE
                }.onFailure {
                    poiListAdapter.setData(null)
                    recycler.visibility = View.GONE
                    ToastUtils.showShort("${it.message}，请检查地图服务权限和网络")
                }
            }
        }, 300)
    }

    private fun reverseGeocode(location: LatLng, version: Int) {
        updatePoi(location, coordinateName(location), null)
        placeSearch.reverse(location) { result ->
            if (isDestroyed || version != geocodeVersion ||
                !samePoint(location, aMap.cameraPosition.target)) return@reverse
            result.onSuccess { address -> updatePoi(location, address.name, address.city) }
                .onFailure { ToastUtils.showShort("${it.message}，已保留坐标，可直接确认") }
        }
    }

    private fun updatePoi(location: LatLng, name: String?, city: String?, uid: String? = null) {
        selectedPoi = PoiInfoModel(location, uid?.takeIf { it.isNotBlank() } ?: location.toString(),
            name?.takeIf { it.isNotBlank() } ?: coordinateName(location), poiInfoType, city.orEmpty())
        if (!city.isNullOrBlank()) detectedCity = city
        updateSearchCityLabel()
        tv_poi_name.text = selectedPoi?.name
        tv_lonlat.text = String.format(Locale.US, "纬度 %.6f  ·  经度 %.6f", location.latitude, location.longitude)
        confirm_location.isEnabled = true
        confirm_location.alpha = 1f
    }
    private fun coordinateName(location: LatLng) =
        String.format(Locale.US, "坐标 %.6f, %.6f", location.latitude, location.longitude)
    private fun samePoint(a: LatLng?, b: LatLng?) = a != null && b != null &&
        abs(a.latitude - b.latitude) < 0.000001 && abs(a.longitude - b.longitude) < 0.000001
    private fun changeCenterLatLng(location: LatLng) {
        aMap.animateTo(location, 16f)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.tv_search_city -> {
                KeyboardUtils.hideSoftInput(et_search)
                editViewShow(true)
                cityPicker?.show(dataBinding.city)
            }
            R.id.iv_search -> {
                editViewShow(true)
                et_search.requestFocus()
                KeyboardUtils.showSoftInput(et_search)
                scheduleSuggestions()
            }
            R.id.confirm_location -> {
                val poi = selectedPoi ?: return
                val point = poi.latLng ?: return
                if (!point.latitude.isFinite() || !point.longitude.isFinite()) {
                    ToastUtils.showShort("数据异常，请重新选择")
                    return
                }
                setResult(RESULT_OK, Intent().putExtra("poiInfo", poi).apply {
                    if (index != -1) putExtra("index", index)
                })
                finish()
            }
            R.id.iv_cur_location -> locateCurrentPosition()
            R.id.iv_back -> finish()
            R.id.tv_input -> {
                cancelLocationRequest()
                mapLocationManager?.stopFollowing()
                inputDialog?.dismiss()
                inputDialog = InputLatLngDialog(this, object : InputLatLngDialog.InputLatLngDialogListener {
                    override fun onConfirm(latLng: LatLng) {
                        selectedSearchPoint = null
                        changeCenterLatLng(latLng)
                    }
                }).apply { show() }
            }
        }
    }

    private fun hasLocationPermission() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun locateCurrentPosition() {
        if (isLocating) return
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val systemLocation = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(systemLocation)) {
            showLocationError("系统定位未开启，请开启后重试")
            return
        }
        val manager = mapLocationManager ?: return
        editViewShow(false)
        geocodeVersion++
        selectedSearchPoint = null
        setLocating(true)
        location_status.text = "正在获取当前位置…"
        location_status.visibility = View.VISIBLE
        val version = ++locationRequestVersion
        manager.requestCurrentLocation { result ->
            if (isDestroyed || isFinishing || version != locationRequestVersion) return@requestCurrentLocation
            setLocating(false)
            result.onSuccess { point ->
                location_status.visibility = View.GONE
                selectedSearchPoint = null
                updatePoi(point, coordinateName(point), null)
                if (samePoint(point, aMap.cameraPosition.target)) {
                    reverseGeocode(point, ++geocodeVersion)
                } else changeCenterLatLng(point)
            }.onFailure { showLocationError(it.message ?: "定位失败，请重试") }
        }
    }

    private fun setLocating(loading: Boolean) {
        isLocating = loading
        iv_cur_location.isEnabled = !loading
        iv_cur_location.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        location_progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showLocationError(message: String) {
        setLocating(false)
        location_status.text = message
        location_status.visibility = View.VISIBLE
    }

    private fun cancelLocationRequest() {
        locationRequestVersion++
        mapLocationManager?.cancelCurrentLocationRequest()
        setLocating(false)
        location_status.visibility = View.GONE
    }

    private fun editViewShow(show: Boolean) {
        dataBinding.isShowSearch = show
        if (!show) {
            searchVersion++
            et_search.clearFocus()
            KeyboardUtils.hideSoftInput(et_search)
            recycler.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        if (dataBinding.isShowSearch == true) editViewShow(false)
        else super.onBackPressed()
    }
    override fun onResume() { super.onResume(); mapview.onResume() }
    override fun onPause() { cancelLocationRequest(); mapview.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapview.onSaveInstanceState(outState)
        outState.putBoolean("city_selection_made", citySelectionMade)
        selectedSearchCity?.let {
            outState.putString("search_city_code", it.code)
            outState.putString("search_city_name", it.name)
        }
    }
    override fun onDestroy() {
        geocodeVersion++
        searchVersion++
        handler.removeCallbacksAndMessages(null)
        inputDialog?.dismiss()
        cityPicker?.dismiss()
        placeSearch.destroy()
        mapLocationManager?.onDestroy()
        mapview.onDestroy()
        super.onDestroy()
    }
}
