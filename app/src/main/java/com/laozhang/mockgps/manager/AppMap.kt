package com.laozhang.mockgps.manager

import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.view.MotionEvent
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.LocationSource
import com.amap.api.maps.model.*
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory

class AppPolyline(private val removeOverlay: () -> Unit) { fun remove() = removeOverlay() }

class AppMap(val provider: MapProvider, private val amap: AMap?, private val baidu: BaiduMap?) {
    private var loaded = false
    private var destroyed = false
    private var rotateLocation = false
    private var loadedListener: (() -> Unit)? = null
    private var locationListener: LocationSource.OnLocationChangedListener? = null
    var myLocation: LatLng? = null
        private set
    val cameraPosition: CameraPosition
        get() = amap?.cameraPosition ?: baidu?.mapStatus?.let {
            CameraPosition(it.target.toAppPoint(), it.zoom, 0f, it.rotate)
        } ?: CameraPosition(LatLng(39.9, 116.4), 12f, 0f, 0f)

    init {
        amap?.uiSettings?.apply {
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = false
        }
        baidu?.uiSettings?.apply {
            isCompassEnabled = false
            isOverlookingGesturesEnabled = false
            isRotateGesturesEnabled = false
        }
        amap?.setOnMapLoadedListener { onLoaded() }
        baidu?.setOnMapLoadedCallback { onLoaded() }
    }

    private fun onLoaded() {
        if (destroyed) return
        loaded = true
        loadedListener?.invoke()
    }

    fun setOnMapLoadedListener(listener: () -> Unit) {
        loadedListener = listener
        if (loaded && !destroyed) listener()
    }

    fun setGesturesEnabled(enabled: Boolean) {
        amap?.uiSettings?.setAllGesturesEnabled(enabled)
        baidu?.uiSettings?.setAllGesturesEnabled(enabled)
    }

    fun setOnMapTouchListener(listener: (MotionEvent) -> Unit) {
        amap?.setOnMapTouchListener { if (!destroyed) listener(it) }
        baidu?.setOnMapTouchListener { if (!destroyed) listener(it) }
    }

    fun setOnCameraChangeListener(listener: OnCameraChangeListener) {
        amap?.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) {
                if (!destroyed) listener.onCameraChange(position)
            }
            override fun onCameraChangeFinish(position: CameraPosition?) {
                if (!destroyed) listener.onCameraChangeFinish(position)
            }
        })
        baidu?.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
            override fun onMapStatusChangeStart(status: MapStatus?) {}
            override fun onMapStatusChangeStart(status: MapStatus?, reason: Int) {}
            override fun onMapStatusChange(status: MapStatus?) {
                if (!destroyed) listener.onCameraChange(cameraPosition)
            }
            override fun onMapStatusChangeFinish(status: MapStatus?) {
                if (!destroyed) listener.onCameraChangeFinish(cameraPosition)
            }
        })
    }

    fun animateTo(point: LatLng, zoom: Float) {
        amap?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom))
        baidu?.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(point.toBaiduPoint(), zoom))
    }

    fun setZoom(zoom: Float) {
        amap?.moveCamera(CameraUpdateFactory.zoomTo(zoom))
        baidu?.setMapStatus(MapStatusUpdateFactory.zoomTo(zoom))
    }

    fun clear() { amap?.clear(); baidu?.clear() }

    fun addMarker(point: LatLng, assetName: String) {
        amap?.addMarker(MarkerOptions().position(point)
            .icon(BitmapDescriptorFactory.fromAsset(assetName)).zIndex(2f))
        baidu?.addOverlay(com.baidu.mapapi.map.MarkerOptions().position(point.toBaiduPoint())
            .icon(com.baidu.mapapi.map.BitmapDescriptorFactory.fromAsset(assetName)).zIndex(2))
    }

    fun addLine(points: List<LatLng>, main: Boolean): AppPolyline? {
        if (points.size < 2) return null
        amap?.let { map ->
            val line = map.addPolyline(PolylineOptions().addAll(points).width(30f)
                .setUseTexture(true).setCustomTexture(BitmapDescriptorFactory.fromAsset(
                    if (main) "navi_lbs_texture.png" else "navi_lbs_texture_unselected.png"))
                .zIndex(if (main) 1f else 0f))
            return AppPolyline { line.remove() }
        }
        val line = baidu?.addOverlay(com.baidu.mapapi.map.PolylineOptions()
            .points(points.map { it.toBaiduPoint() }).width(12f)
            .color(if (main) Color.rgb(70, 136, 190) else Color.LTGRAY)
            .zIndex(if (main) 1 else 0)) ?: return null
        return AppPolyline { line.remove() }
    }

    fun fitPoints(points: List<LatLng>, padding: Rect) {
        amap?.let { map ->
            val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBoundsRect(bounds,
                padding.left, padding.right, padding.top, padding.bottom))
        }
        baidu?.let { map ->
            val bounds = com.baidu.mapapi.model.LatLngBounds.Builder().apply {
                points.forEach { include(it.toBaiduPoint()) }
            }.build()
            map.animateMapStatus(MapStatusUpdateFactory.newLatLngBounds(bounds,
                padding.left, padding.top, padding.right, padding.bottom))
        }
    }

    fun enableLocation() {
        amap?.setLocationSource(object : LocationSource {
            override fun activate(listener: LocationSource.OnLocationChangedListener?) { locationListener = listener }
            override fun deactivate() { locationListener = null }
        })
        amap?.isMyLocationEnabled = true
        baidu?.isMyLocationEnabled = true
        setLocationMode(false)
    }

    fun updateLocation(point: LatLng, accuracy: Float, direction: Float) {
        if (destroyed) return
        myLocation = point
        locationListener?.onLocationChanged(Location("app_location").apply {
            latitude = point.latitude; longitude = point.longitude
            this.accuracy = accuracy; bearing = direction
        })
        baidu?.setMyLocationData(com.baidu.mapapi.map.MyLocationData.Builder()
            .latitude(point.latitude).longitude(point.longitude).accuracy(accuracy).direction(direction).build())
        if (rotateLocation && direction.isFinite() && direction >= 0f) {
            baidu?.let { map ->
                map.setMapStatus(MapStatusUpdateFactory.newMapStatus(
                    MapStatus.Builder(map.mapStatus).rotate(direction).build()))
            }
        }
    }

    fun setLocationMode(rotate: Boolean) {
        rotateLocation = rotate
        amap?.myLocationStyle = MyLocationStyle().myLocationType(
            if (rotate) MyLocationStyle.LOCATION_TYPE_MAP_ROTATE_NO_CENTER
            else MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            .radiusFillColor(Color.TRANSPARENT).strokeColor(Color.TRANSPARENT)
        baidu?.setMyLocationConfiguration(com.baidu.mapapi.map.MyLocationConfiguration(
            com.baidu.mapapi.map.MyLocationConfiguration.LocationMode.NORMAL, true, null,
            Color.TRANSPARENT, Color.TRANSPARENT))
        if (!rotate) {
            amap?.moveCamera(CameraUpdateFactory.changeBearing(0f))
            baidu?.let { map ->
                map.setMapStatus(MapStatusUpdateFactory.newMapStatus(
                    MapStatus.Builder(map.mapStatus).rotate(0f).build()))
            }
        }
    }

    fun disableLocation() {
        amap?.isMyLocationEnabled = false
        baidu?.isMyLocationEnabled = false
        locationListener = null
    }

    fun destroy() { destroyed = true; loadedListener = null; disableLocation() }

    interface OnCameraChangeListener {
        fun onCameraChange(position: CameraPosition?)
        fun onCameraChangeFinish(position: CameraPosition?)
    }
}

fun LatLng.toBaiduPoint() = com.baidu.mapapi.model.LatLng(latitude, longitude)
fun com.baidu.mapapi.model.LatLng.toAppPoint() = LatLng(latitude, longitude)
