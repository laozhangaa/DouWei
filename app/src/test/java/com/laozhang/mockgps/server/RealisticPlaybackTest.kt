package com.laozhang.mockgps.server

import com.laozhang.mockgps.model.*
import org.junit.Assert.*
import org.junit.Test

class RealisticPlaybackTest {
    private val origin = RouteCoordinate(26.0, 106.0)
    private fun east(meters: Double) = RouteCoordinate(origin.latitude,
        origin.longitude + Math.toDegrees(meters / (6_371_000 * kotlin.math.cos(Math.toRadians(origin.latitude)))))
    private val options = NavigationOptions(realistic = true, simulateStops = true, stopSeconds = 5)
    private fun ns(tick: Int) = tick * 100_000_000L

    @Test fun startupAcceleratesAndSpeedMatchesIntegratedTravel() {
        val playback = RoutePlayback(listOf(origin, east(10_000.0)), options)
        var previous = playback.advance(0, 120 / 3.6f)
        assertEquals(0f, previous.speed, 0f)
        for (tick in 1..400) {
            val frame = playback.advance(ns(tick), 120 / 3.6f)
            assertTrue(frame.speed <= 120 / 3.6f + 0.001f)
            assertTrue(frame.speed - previous.speed <= 0.201f)
            assertEquals((frame.speed + previous.speed) / 2 * 0.1,
                frame.distanceMeters - previous.distanceMeters, 0.002)
            previous = frame
        }
        assertEquals(120 / 3.6f, previous.speed, 0.001f)
    }

    @Test fun sparseRightAngleSlowsBeforeJunctionThenAccelerates() {
        val corner = east(500.0)
        val end = RouteCoordinate(corner.latitude + Math.toDegrees(1000.0 / 6_371_000), corner.longitude)
        val playback = RoutePlayback(listOf(origin, corner, end), options)
        var nearCorner = false
        var acceleratedAgain = false
        for (tick in 0..1500) {
            val frame = playback.advance(ns(tick), 120 / 3.6f)
            if (kotlin.math.abs(frame.distanceMeters - 500) < 2) {
                nearCorner = true
                assertTrue("Sharp corner at ${frame.speed * 3.6} km/h", frame.speed < 8f)
            }
            if (frame.distanceMeters in 750.0..1000.0 && frame.speed > 20) acceleratedAgain = true
        }
        assertTrue(nearCorner)
        assertTrue(acceleratedAgain)
    }

    @Test fun simulatedStopBrakesWaitsThenContinuesWithoutSkippingRoad() {
        val playback = RoutePlayback(listOf(origin, east(1000.0)), options, listOf(SimulatedStop(200.0, 5)))
        var stoppedTicks = 0
        var movedAfterStop = false
        var previous = playback.advance(0, 120 / 3.6f)
        for (tick in 1..1500) {
            val frame = playback.advance(ns(tick), 120 / 3.6f)
            assertTrue(frame.distanceMeters >= previous.distanceMeters)
            assertTrue(frame.distanceMeters - previous.distanceMeters < 3.34)
            if (frame.stopRemainingSeconds > 0) {
                stoppedTicks++
                assertEquals(200.0, frame.distanceMeters, 0.001)
                assertEquals(0f, frame.speed, 0f)
            }
            if (stoppedTicks > 0 && frame.distanceMeters > 201) movedAfterStop = true
            previous = frame
        }
        assertTrue("Dwell ticks: $stoppedTicks", stoppedTicks in 49..51)
        assertTrue(movedAfterStop)
        assertTrue(previous.finished)
        assertEquals(0f, previous.speed, 0f)
    }

    @Test fun pauseAndRestoreDuringStopRetainRemainingDwellButExcludeWallTime() {
        val points = listOf(origin, east(1000.0))
        val stops = listOf(SimulatedStop(100.0, 10))
        val playback = RoutePlayback(points, options, stops)
        var tick = 0
        while (tick < 1000 && playback.advance(ns(tick), 20f).stopRemainingSeconds == 0) tick++
        playback.advance(ns(tick + 20), 20f)
        val cursor = playback.position()
        assertTrue(cursor.stopRemainingSeconds in 7.0..8.1)
        playback.resetClock()
        val paused = playback.advance(ns(tick + 10_000), 20f)
        assertEquals(cursor.distanceMeters, paused.distanceMeters, 0f.toDouble())
        assertEquals(cursor, playback.position())
        val restored = RoutePlayback(points, options, stops)
        restored.restore(cursor)
        restored.advance(0, 20f)
        assertEquals(cursor, restored.position())
        val next = restored.advance(1_000_000_000L, 20f)
        assertEquals(100.0, next.distanceMeters, 0.001)
        assertEquals(cursor.stopRemainingSeconds - 1, restored.position().stopRemainingSeconds, 0.001)
    }

    @Test fun seekSkipsEarlierStopsAndRestartAllowsThemAgain() {
        val playback = RoutePlayback(listOf(origin, east(1000.0)), options, listOf(SimulatedStop(100.0, 5)))
        playback.seekFraction(0.5)
        assertEquals(1, playback.position().completedStops)
        assertEquals(500.0, playback.advance(0, 20f).distanceMeters, 0.01)
        playback.seekFraction(0.0)
        assertEquals(PlaybackPosition(), playback.position())
        playback.seekFraction(1.0)
        assertTrue(playback.advance(0, 20f).finished)
        playback.seekFraction(0.25)
        assertFalse(playback.advance(0, 20f).finished)
    }

    @Test fun uniformModeIgnoresStopsAndInvalidRestoreCannotSkipFutureStop() {
        val points = listOf(origin, east(1000.0))
        val stop = listOf(SimulatedStop(100.0, 5))
        val uniform = RoutePlayback(points, NavigationOptions(), stop)
        uniform.advance(0, 20f)
        assertEquals(200.0, uniform.advance(10_000_000_000L, 20f).distanceMeters, 0.01)
        val real = RoutePlayback(points, options, stop)
        listOf(PlaybackPosition(2000.0), PlaybackPosition(10.0, 1),
            PlaybackPosition(101.0, 0), PlaybackPosition(10.0, 1, 4.0),
            PlaybackPosition(Double.NaN)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { real.restore(bad) }
        }
    }
}
