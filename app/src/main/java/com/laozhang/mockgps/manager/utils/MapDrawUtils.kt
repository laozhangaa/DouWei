package com.laozhang.mockgps.manager.utils

import android.graphics.Rect
import com.amap.api.maps.model.LatLng
import com.laozhang.mockgps.manager.AppMap
import com.laozhang.mockgps.manager.AppPolyline

object MapDrawUtils {
    fun drawMarkerToMap(map: AppMap, point: LatLng, assetName: String) {
        if (assetName.isNotBlank()) map.addMarker(point, assetName)
    }

    fun drawLineToMap(map: AppMap, polylineList: List<LatLng>, rect: Rect,
                      isMainLine: Boolean = true, animateMapStatus: Boolean = true): AppPolyline? {
        val line = map.addLine(polylineList, isMainLine) ?: return null
        if (isMainLine && animateMapStatus) map.fitPoints(polylineList, rect)
        return line
    }
}
