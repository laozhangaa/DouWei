package com.laozhang.mockgps.server

import com.laozhang.mockgps.model.RouteCoordinate
import org.junit.Assert.*
import org.junit.Test

class RoutePlaybackTest {
    private val origin = RouteCoordinate(26.0, 106.0)
    private fun east(meters: Double) = RouteCoordinate(origin.latitude,
        origin.longitude + Math.toDegrees(meters / (6_371_000 * kotlin.math.cos(Math.toRadians(origin.latitude)))))
    private fun ns(seconds: Double) = (seconds * 1_000_000_000L).toLong()

    @Test fun at120KmhEvery100msMovesAbout333cmAcrossDenseVertices() {
        val playback = RoutePlayback((0..1000).map { east(it * 2.0) })
        val speed = 120f / 3.6f
        var previous = playback.advance(0, speed)
        for (tick in 1..100) {
            val frame = playback.advance(tick * 100_000_000L, speed)
            assertEquals(speed * 0.1, RoutePlayback.meters(previous.coordinate, frame.coordinate), 0.002)
            assertEquals(speed, frame.speed, 0f)
            assertFalse(frame.finished)
            previous = frame
        }
        assertEquals(333.3333, RoutePlayback.meters(origin, previous.coordinate), 0.01)
    }

    @Test fun irregularSchedulingUsesElapsedTimeRatherThanAssumingOneSecond() {
        val playback = RoutePlayback(listOf(origin, east(5000.0)))
        playback.advance(0, 120f / 3.6f)
        listOf(0.09, 0.23, 0.34, 1.1, 2.7, 10.0).forEach { seconds ->
            val frame = playback.advance(ns(seconds), 120f / 3.6f)
            assertEquals(seconds * 120 / 3.6, RoutePlayback.meters(origin, frame.coordinate), 0.01)
        }
    }

    @Test fun longDrivingRouteAt120KmhCovers120KmInAnHourWithSmoothTicks() {
        val playback = RoutePlayback((0..40_000).map { east(it * 10.0) })
        val speed = 120f / 3.6f
        var frame = playback.advance(0, speed)
        for (tick in 1..36_000) {
            val next = playback.advance(tick * 100_000_000L, speed)
            assertEquals(3.333333, RoutePlayback.meters(frame.coordinate, next.coordinate), 0.002)
            assertTrue(next.nextIndex >= frame.nextIndex)
            frame = next
        }
        // A thousand samples per 10 km, without stopping for each geometry vertex.
        assertTrue(frame.nextIndex in 11_999..12_001)
        assertFalse(frame.finished)
        assertEquals(speed, frame.speed, 0f)
    }

    @Test fun remainingDistanceContinuesAroundACornerInsteadOfCuttingIt() {
        val corner = east(10.0)
        val end = RouteCoordinate(corner.latitude + Math.toDegrees(100.0 / 6_371_000), corner.longitude)
        val playback = RoutePlayback(listOf(origin, corner, end))
        playback.advance(0, 20f)
        val frame = playback.advance(ns(1.0), 20f)
        assertEquals(corner.longitude, frame.coordinate.longitude, 1e-9)
        assertEquals(10.0, RoutePlayback.meters(corner, frame.coordinate), 0.01)
        assertEquals(0f, frame.bearing, 0.01f)
        assertEquals(2, frame.nextIndex)
    }

    @Test fun crossingTheSameRoadLaterNeverSnapsBackToAnEarlierSegment() {
        val north = RouteCoordinate(26.001, 106.0)
        val east = RouteCoordinate(26.001, 106.001)
        val south = RouteCoordinate(25.999, 106.001)
        val playback = RoutePlayback(listOf(origin, north, east, south, north))
        playback.seek(2)
        playback.advance(0, 10f)
        val frame = playback.advance(ns(1.0), 10f)
        assertEquals(106.001, frame.coordinate.longitude, 1e-9)
        assertTrue(frame.coordinate.latitude < east.latitude)
        assertEquals(3, frame.nextIndex)
        assertEquals(180f, frame.bearing, 0.01f)
    }

    @Test fun pauseResumeDoesNotIncludePausedTime() {
        val playback = RoutePlayback(listOf(origin, east(5000.0)))
        playback.advance(0, 10f)
        val before = playback.advance(ns(1.0), 10f)
        playback.resetClock()
        val resumed = playback.advance(ns(100.0), 10f)
        assertEquals(before.coordinate, resumed.coordinate)
        assertEquals(11.0, RoutePlayback.meters(origin, playback.advance(ns(100.1), 10f).coordinate), 0.01)
    }

    @Test fun seekingAndRestartingDoNotCarryThePreviousTickTime() {
        val playback = RoutePlayback(listOf(origin, east(100.0), east(1000.0)))
        playback.advance(0, 10f)
        playback.advance(ns(2.0), 10f)
        playback.seek(1)
        assertEquals(100.0, RoutePlayback.meters(origin, playback.advance(ns(50.0), 10f).coordinate), 0.01)
        playback.seek(0)
        assertEquals(0.0, RoutePlayback.meters(origin, playback.advance(ns(60.0), 10f).coordinate), 0.01)
    }

    @Test fun endOfRouteStaysAtEndpointAndReportsZeroSpeed() {
        val end = east(5.0)
        val playback = RoutePlayback(listOf(origin, end))
        playback.advance(0, 120f / 3.6f)
        val final = playback.advance(ns(1.0), 120f / 3.6f)
        assertEquals(end, final.coordinate)
        assertTrue(final.finished)
        assertEquals(0f, final.speed, 0f)
        assertEquals(2, final.nextIndex)
        assertEquals(final, playback.advance(ns(100.0), 120f / 3.6f))
    }

    @Test fun thousandsOfDuplicatePointsDoNotRecurseOrLoseProgress() {
        val playback = RoutePlayback(List(50_000) { origin } + east(1000.0))
        playback.advance(0, 10f)
        assertEquals(10.0, RoutePlayback.meters(origin, playback.advance(ns(1.0), 10f).coordinate), 0.01)
        val stationary = RoutePlayback(List(50_000) { origin }).advance(0, 10f)
        assertTrue(stationary.finished)
        assertEquals(0f, stationary.speed, 0f)
        assertEquals(origin, stationary.coordinate)
    }

    @Test fun speedChangesAndZeroSpeedRespectDistanceBudget() {
        val playback = RoutePlayback(listOf(origin, east(1000.0)))
        playback.advance(0, 10f)
        playback.advance(ns(1.0), 10f)
        playback.advance(ns(2.0), 20f)
        val stopped = playback.advance(ns(3.0), 0f)
        assertEquals(30.0, RoutePlayback.meters(origin, stopped.coordinate), 0.01)
        assertEquals(0f, stopped.speed, 0f)
        assertFalse(stopped.finished)
    }

    @Test fun backwardClockNeverMovesBackwardOrCountsTheSameTimeTwice() {
        val playback = RoutePlayback(listOf(origin, east(1000.0)))
        playback.advance(ns(1.0), 10f)
        val frame = playback.advance(ns(2.0), 10f)
        assertEquals(frame, playback.advance(ns(1.5), 10f))
        assertEquals(11.0, RoutePlayback.meters(origin, playback.advance(ns(2.1), 10f).coordinate), 0.01)
    }

    @Test fun antimeridianUsesTheShortSegment() {
        val playback = RoutePlayback(listOf(RouteCoordinate(0.0, 179.999), RouteCoordinate(0.0, -179.999)))
        assertEquals(222.39, playback.totalDistance, 0.1)
        playback.advance(0, 10f)
        val frame = playback.advance(ns(12.0), 10f)
        assertTrue(frame.coordinate.isValid())
        assertTrue(frame.coordinate.longitude < -179.999)
    }

    @Test fun invalidCoordinatesAndSpeedAreRejected() {
        for (points in listOf(emptyList(), listOf(RouteCoordinate(Double.NaN, 106.0)))) {
            try { RoutePlayback(points); fail("Accepted invalid geometry") } catch (_: IllegalArgumentException) { }
        }
        val playback = RoutePlayback(listOf(origin, east(1.0)))
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            try { playback.advance(0, speed); fail("Accepted invalid speed") } catch (_: IllegalArgumentException) { }
        }
    }
}
