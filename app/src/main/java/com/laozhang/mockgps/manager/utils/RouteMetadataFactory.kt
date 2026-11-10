package com.laozhang.mockgps.manager.utils

import com.amap.api.services.route.DrivePathV2
import com.laozhang.mockgps.model.DrivingPreference
import com.laozhang.mockgps.model.RouteCoordinate
import com.laozhang.mockgps.model.RouteMetadata
import kotlin.math.*

/** Search SDKs expose counts, not live lamp phases. Every stop below is explicitly an estimate. */
object RouteMetadataFactory {
    fun fromPath(path: DrivePathV2, provider: String, preference: DrivingPreference): RouteMetadata {
        val lights = path.cost?.trafficLights?.takeIf { it >= 0 }
        var distance = 0.0
        var previous: RouteCoordinate? = null
        val candidates = arrayListOf<Double>()
        for (step in path.steps.orEmpty()) {
            val start = distance
            step.polyline.orEmpty().forEach { point ->
                val coordinate = RouteCoordinate(point.latitude, point.longitude)
                previous?.let { distance += meters(it, coordinate) }
                previous = coordinate
            }
            val road = step.road.orEmpty()
            val instruction = step.instruction.orEmpty()
            val highway = Regex("高速|快速路|匝道|隧道|高架").containsMatchIn(road + instruction)
            val count = step.costDetail?.trafficLights ?: 0
            if (!highway && count > 0) {
                // Counts do not identify exact lamp locations; distribute only within this SDK step.
                for (i in 1..count.coerceAtMost(100)) candidates.add(start + (distance - start) * i / (count + 1.0))
            } else if (!highway && Regex("左转|右转|路口|交叉|红绿灯|信号灯").containsMatchIn(instruction)) {
                candidates.add(distance)
            }
        }
        val spaced = arrayListOf<Double>()
        for (at in candidates.sorted()) {
            if (at > 20 && at < distance - 20 && (spaced.isEmpty() || at - spaced.last() >= 30)) spaced.add(at)
        }
        val stops = when {
            lights == 0 -> emptyList()
            lights != null -> spaced.take(lights.coerceAtMost(10_000))
            else -> spaced.take(10_000)
        }
        return RouteMetadata(provider = provider, preference = preference.name, trafficLightCount = lights,
            roadNames = path.steps.orEmpty().mapNotNull { it.road?.takeIf { road -> road.isNotBlank() } }.distinct().take(100),
            estimatedStopMeters = stops,
            sdkDurationSeconds = (path.cost?.duration?.toLong() ?: path.duration).coerceAtLeast(0),
            tollYuan = path.cost?.tolls?.takeIf { it.isFinite() && it >= 0 })
    }

    private fun meters(a: RouteCoordinate, b: RouteCoordinate): Double {
        val x = Math.toRadians(b.latitude - a.latitude)
        val y = Math.toRadians(b.longitude - a.longitude)
        val h = sin(x / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(y / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
