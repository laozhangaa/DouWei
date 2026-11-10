package com.laozhang.mockgps.server

import com.laozhang.mockgps.model.RouteCoordinate
import com.laozhang.mockgps.model.NavigationOptions
import com.laozhang.mockgps.model.PlaybackPosition
import com.laozhang.mockgps.model.SimulatedStop
import kotlin.math.*

/** Distance along the selected polyline; crossing a vertex never discards remaining travel. */
internal class RoutePlayback(
    points: List<RouteCoordinate>,
    private val options: NavigationOptions = NavigationOptions(),
    stops: List<SimulatedStop> = emptyList()
) {
    data class Frame(
        val coordinate: RouteCoordinate,
        val bearing: Float,
        val nextIndex: Int,
        val speed: Float,
        val finished: Boolean,
        val distanceMeters: Double = 0.0,
        val totalMeters: Double = 0.0,
        val remainingSeconds: Long = 0,
        val stopRemainingSeconds: Int = 0
    )

    private val points = points.toList()
    private val cumulative = DoubleArray(points.size)
    private var distance = 0.0
    private var previousNanos: Long? = null
    private var currentSpeed = 0f
    private var completedStops = 0
    private var stopRemaining = 0.0
    private val stops: List<SimulatedStop>
    private val curves = arrayListOf<Pair<Double, Double>>()
    val totalDistance: Double get() = cumulative.last()

    init {
        require(points.isNotEmpty() && points.all { it.isValid() }) { "路线坐标无效" }
        options.validate()
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + meters(points[i - 1], points[i])
        }
        this.stops = if (options.realistic && options.simulateStops) stops.onEach { it.validate() }
            .filter { it.distanceMeters > 1.0 && it.distanceMeters < totalDistance - 1.0 }
            .sortedBy { it.distanceMeters }.distinctBy { it.distanceMeters.toInt() } else emptyList()
        if (options.realistic) for (i in 1 until points.lastIndex) {
            val before = cumulative[i] - cumulative[i - 1]
            val after = cumulative[i + 1] - cumulative[i]
            if (before < 0.2 || after < 0.2) continue
            val angle = abs((heading(points[i], points[i + 1]) - heading(points[i - 1], points[i]) + 540f) % 360f - 180f)
            if (angle >= 30f) {
                val geometricRadius = min(before, after) / (2 * sin(Math.toRadians(angle.toDouble()) / 2))
                // Sparse SDK geometry must not turn a sharp junction into a high-speed bend.
                val radius = min(geometricRadius, if (angle >= 75) 25.0 else if (angle >= 45) 45.0 else 80.0)
                curves.add(cumulative[i] to sqrt(1.8 * radius).coerceAtLeast(6.0 / 3.6))
            }
        }
    }

    @Synchronized fun seek(index: Int) {
        seekDistance(cumulative[index.coerceIn(points.indices)])
    }

    @Synchronized fun seekFraction(fraction: Double) {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        seekDistance(totalDistance * fraction)
    }

    private fun seekDistance(value: Double) {
        distance = value.coerceIn(0.0, totalDistance)
        completedStops = stops.count { it.distanceMeters <= distance }
        stopRemaining = 0.0
        currentSpeed = 0f
        previousNanos = null
    }

    @Synchronized fun position() = PlaybackPosition(distance, completedStops, stopRemaining)

    @Synchronized fun restore(position: PlaybackPosition) {
        position.validate()
        require(position.distanceMeters <= totalDistance + 1.0 && position.completedStops <= stops.size) { "恢复点超出当前路线" }
        val restoredDistance = position.distanceMeters.coerceAtMost(totalDistance)
        val restoredStops = position.completedStops
        require(restoredStops == 0 || stops[restoredStops - 1].distanceMeters <= restoredDistance + 1.0) { "恢复停车进度超出已行驶道路" }
        if (position.stopRemainingSeconds > 0) {
            require(restoredStops > 0 && abs(restoredDistance - stops[restoredStops - 1].distanceMeters) < 1.0) { "恢复停车点与路线不匹配" }
        }
        require(stops.drop(restoredStops).none { it.distanceMeters < restoredDistance - 0.2 }) { "恢复停车进度无效" }
        distance = restoredDistance
        completedStops = restoredStops
        stopRemaining = position.stopRemainingSeconds
        currentSpeed = 0f
        previousNanos = null
    }

    /** Paused time must never be counted as travel when injection resumes. */
    @Synchronized fun resetClock() { previousNanos = null; currentSpeed = 0f }

    @Synchronized fun advance(nowNanos: Long, speedMetersPerSecond: Float): Frame {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0f)
        val previous = previousNanos
        if (previous == null || nowNanos >= previous) {
            val seconds = if (previous == null) 0.0 else (nowNanos - previous) / 1_000_000_000.0
            if (options.realistic) advanceRealistic(seconds, speedMetersPerSecond)
            else {
                distance = (distance + seconds * speedMetersPerSecond).coerceAtMost(totalDistance)
                currentSpeed = speedMetersPerSecond
            }
            previousNanos = nowNanos
        }
        if (distance >= totalDistance) {
            val prior = (points.lastIndex - 1 downTo 0).firstOrNull {
                cumulative[it] < totalDistance
            }
            return Frame(points.last(), prior?.let { heading(points[it], points.last()) } ?: 0f,
                points.size, 0f, true, totalDistance, totalDistance)
        }
        // First vertex strictly beyond the travelled distance, also skipping duplicate points.
        var low = 1
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulative[middle] <= distance) low = middle + 1 else high = middle
        }
        val start = points[low - 1]
        val end = points[low]
        val fraction = (distance - cumulative[low - 1]) / (cumulative[low] - cumulative[low - 1])
        val longitude = start.longitude + longitudeDelta(start.longitude, end.longitude) * fraction
        val remainingStops = stops.drop(completedStops).sumOf { it.seconds }.toDouble() + stopRemaining
        val eta = if (speedMetersPerSecond > 0) ceil((totalDistance - distance) / speedMetersPerSecond + remainingStops).toLong() else 0L
        return Frame(RouteCoordinate(start.latitude + (end.latitude - start.latitude) * fraction,
            (longitude + 540.0) % 360.0 - 180.0), heading(start, end), low, currentSpeed, false,
            distance, totalDistance, eta, ceil(stopRemaining).toInt())
    }

    private fun advanceRealistic(seconds: Double, maximumSpeed: Float) {
        var remaining = seconds
        while (remaining > 0 && distance < totalDistance) {
            val dt = min(remaining, 0.1)
            remaining -= dt
            if (stopRemaining > 0) {
                val dwell = min(dt, stopRemaining)
                stopRemaining -= dwell
                currentSpeed = 0f
                // Include the unused part of the tick after a stop ends.
                remaining += dt - dwell
                continue
            }
            val stop = stops.getOrNull(completedStops)
            val nextStopDistance = stop?.distanceMeters ?: totalDistance
            val toStop = (nextStopDistance - distance).coerceAtLeast(0.0)
            if (toStop <= 0.15 && maximumSpeed > 0) {
                distance = nextStopDistance
                currentSpeed = 0f
                if (stop != null) {
                    completedStops++
                    stopRemaining = stop.seconds.toDouble()
                }
                continue
            }
            val deceleration = 2.5
            var target = min(maximumSpeed.toDouble(), sqrt(2 * deceleration * toStop))
            val lookAhead = maximumSpeed * maximumSpeed / (2 * deceleration) + 50
            var low = 0
            var high = curves.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (curves[mid].first < distance - 2.0) low = mid + 1 else high = mid
            }
            for (i in low until curves.size) {
                val (at, limit) = curves[i]
                if (at - distance > lookAhead) break
                val brakingMargin = max(currentSpeed, maximumSpeed) * dt
                target = min(target, sqrt(limit * limit + 2 * deceleration * (at - distance - brakingMargin).coerceAtLeast(0.0)))
            }
            val oldSpeed = currentSpeed.toDouble()
            val nextSpeed = if (target >= oldSpeed) min(target, oldSpeed + 2.0 * dt)
                else max(target, oldSpeed - deceleration * dt)
            val travel = (oldSpeed + nextSpeed) * 0.5 * dt
            distance = min(nextStopDistance, distance + travel)
            currentSpeed = nextSpeed.toFloat()
        }
    }

    companion object {
        private fun longitudeDelta(a: Double, b: Double) = (b - a + 540.0) % 360.0 - 180.0
        internal fun meters(a: RouteCoordinate, b: RouteCoordinate): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(longitudeDelta(a.longitude, b.longitude))
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
                cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6_371_000.0 * 2.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        private fun heading(a: RouteCoordinate, b: RouteCoordinate): Float {
            val lon = Math.toRadians(longitudeDelta(a.longitude, b.longitude))
            val first = Math.toRadians(a.latitude)
            val second = Math.toRadians(b.latitude)
            return ((Math.toDegrees(atan2(sin(lon) * cos(second),
                cos(first) * sin(second) - sin(first) * cos(second) * cos(lon))) + 360.0) % 360.0).toFloat()
        }
    }
}
