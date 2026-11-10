package com.laozhang.mockgps.manager.utils

import com.amap.api.maps.model.LatLng
import com.amap.api.services.route.DrivePathV2
import com.amap.api.services.route.DriveStepV2
import com.amap.api.services.core.LatLonPoint
import com.baidu.mapapi.search.route.DrivingRouteLine
import com.baidu.mapapi.search.route.WalkingRouteLine
import com.baidu.mapapi.search.route.BikingRouteLine
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.RidePath
import com.amap.api.services.route.Cost
import com.laozhang.mockgps.model.RouteCoordinate
import kotlin.math.*

object MapConvertUtils {
    // DrivePathV2 is the existing app route container; no AMap request is made here.
    fun fromBaiduRoute(route: DrivingRouteLine): DrivePathV2 = DrivePathV2().apply {
        distance = route.distance.toFloat()
        duration = route.duration.toLong()
        cost = Cost().apply {
            duration = route.duration.toFloat()
            trafficLights = route.lightNum
            tolls = route.toll.toFloat()
        }
        steps = route.allStep.orEmpty().map { step ->
            DriveStepV2().apply {
                instruction = step.instructions
                road = step.roadName
                stepDistance = step.distance
                polyline = step.wayPoints.orEmpty().map { LatLonPoint(it.latitude, it.longitude) }
            }
        }
    }

    fun fromBaiduWalk(route: WalkingRouteLine): DrivePathV2 = DrivePathV2().apply {
        distance = route.distance.toFloat()
        duration = route.duration.toLong()
        steps = route.allStep.orEmpty().map { step -> DriveStepV2().apply {
            instruction = step.instructions
            polyline = step.wayPoints.orEmpty().map { LatLonPoint(it.latitude, it.longitude) }
        } }
    }

    fun fromBaiduRide(route: BikingRouteLine): DrivePathV2 = DrivePathV2().apply {
        distance = route.distance.toFloat()
        duration = route.duration.toLong()
        steps = route.allStep.orEmpty().map { step -> DriveStepV2().apply {
            instruction = step.instructions
            polyline = step.wayPoints.orEmpty().map { LatLonPoint(it.latitude, it.longitude) }
        } }
    }

    fun fromAmapWalk(route: WalkPath): DrivePathV2 = DrivePathV2().apply {
        distance = route.distance
        duration = route.duration
        steps = route.steps.orEmpty().map { step -> DriveStepV2().apply {
            instruction = step.instruction
            road = step.road
            polyline = step.polyline.orEmpty()
        } }
    }

    fun fromAmapRide(route: RidePath): DrivePathV2 = DrivePathV2().apply {
        distance = route.distance
        duration = route.duration
        steps = route.steps.orEmpty().map { step -> DriveStepV2().apply {
            instruction = step.instruction
            road = step.road
            polyline = step.polyline.orEmpty()
        } }
    }

    fun hasRoadGeometry(path: DrivePathV2): Boolean {
        val raw = path.steps.orEmpty().flatMap { it.polyline.orEmpty() }
        if (raw.any { !RouteCoordinate(it.latitude, it.longitude).isValid() }) return false
        val points = convertLatLngList(path)
        return points.size >= 2 && points.any { it != points.first() } && points.all {
            RouteCoordinate(it.latitude, it.longitude).isValid()
        }
    }

    /** Reject missing legs and disconnected snapped endpoints rather than filling a straight line. */
    fun mergeRoadSegments(segments: List<DrivePathV2>): DrivePathV2 {
        require(segments.isNotEmpty() && segments.all { hasRoadGeometry(it) }) { "部分途经路段没有道路数据，请重新选点" }
        segments.zipWithNext().forEach { (a, b) ->
            require(distanceMeters(convertLatLngList(a).last(), convertLatLngList(b).first()) <= 150) {
                "途经点附近的道路不连通，请将途经点移到可通行道路上"
            }
        }
        return DrivePathV2().apply {
            distance = segments.sumOf { it.distance.toDouble() }.toFloat()
            duration = segments.sumOf { it.duration }
            steps = segments.flatMap { it.steps.orEmpty() }
        }
    }

    fun fromCoordinates(points: List<RouteCoordinate>): DrivePathV2 = DrivePathV2().apply {
        steps = listOf(DriveStepV2().apply { polyline = points.map { LatLonPoint(it.latitude, it.longitude) } })
        distance = points.zipWithNext().sumOf { (a, b) ->
            distanceMeters(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude))
        }.toFloat()
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun convertLatLngList(routeLine: DrivePathV2?): List<LatLng> {
        val points = arrayListOf<LatLng>()
        routeLine?.steps?.forEach { step ->
            step.polyline?.forEach { point ->
                val latLng = LatLng(point.latitude, point.longitude)
                if (points.lastOrNull() != latLng) points.add(latLng)
            }
        }
        return points
    }
}
