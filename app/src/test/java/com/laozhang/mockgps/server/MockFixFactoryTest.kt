package com.laozhang.mockgps.server

import org.junit.Assert.*
import org.junit.Test

class MockFixFactoryTest {
    private val target = MockLocationSnapshot(39.90742, 116.391227, 39.908823, 116.39747, 0f, 0f)

    @Test fun threeSourcesAgreeOnTargetAndStationaryMovementWithCompleteAccuracy() {
        val factory = MockFixFactory()
        val fixes = MockProviderProfile.values().associateWith { factory.create(it, target, 1000L, 1_000_000_000L) }
        fixes.values.forEach {
            assertEquals(target, it.snapshot)
            assertEquals(0f, it.snapshot.speed, 0f)
            assertEquals(0f, it.snapshot.bearing, 0f)
            assertTrue(it.verticalAccuracy > 0 && it.speedAccuracy > 0 && it.bearingAccuracy > 0)
        }
        assertEquals(3f, fixes.getValue(MockProviderProfile.GPS).accuracy, 0f)
        assertEquals(fixes.getValue(MockProviderProfile.GPS), fixes.getValue(MockProviderProfile.FUSED))
        assertEquals(50f, fixes.getValue(MockProviderProfile.NETWORK).accuracy, 0f)
        assertEquals(8, fixes.getValue(MockProviderProfile.GPS).satellites)
        assertNull(fixes.getValue(MockProviderProfile.NETWORK).satellites)
    }

    @Test fun repeatedFixesKeepThePositionAltitudeAndBearingStable() {
        val factory = MockFixFactory()
        val fixes = (0..100).map { factory.create(MockProviderProfile.GPS, target, 1000 + it * 100L, 1_000_000_000L + it * 100_000_000L) }
        assertTrue(fixes.all { it.snapshot == target })
        fixes.zipWithNext().forEach { (a, b) ->
            assertEquals(100L, b.timeMillis - a.timeMillis)
            assertEquals(100_000_000L, b.elapsedRealtimeNanos - a.elapsedRealtimeNanos)
        }
    }

    @Test fun wallClockRollbackAndDuplicateClockReadsNeverProduceOlderFixes() {
        val factory = MockFixFactory()
        for (profile in MockProviderProfile.values()) {
            val first = factory.create(profile, target, 1000, 1_000_000_000)
            val duplicate = factory.create(profile, target, 1000, 1_000_000_000)
            val rollback = factory.create(profile, target, 500, 500_000_000)
            assertTrue(duplicate.timeMillis > first.timeMillis && rollback.timeMillis > duplicate.timeMillis)
            assertTrue(duplicate.elapsedRealtimeNanos > first.elapsedRealtimeNanos && rollback.elapsedRealtimeNanos > duplicate.elapsedRealtimeNanos)
        }
    }

    @Test fun providerClocksAreIndependentAndNetworkFixIsSlightlyOlder() {
        val factory = MockFixFactory()
        val gps = factory.create(MockProviderProfile.GPS, target, 1000, 1_000_000_000)
        val network = factory.create(MockProviderProfile.NETWORK, target, 1000, 1_000_000_000)
        val fused = factory.create(MockProviderProfile.FUSED, target, 1000, 1_000_000_000)
        assertEquals(gps.timeMillis, fused.timeMillis)
        assertEquals(50L, gps.timeMillis - network.timeMillis)
        assertEquals(50_000_000L, gps.elapsedRealtimeNanos - network.elapsedRealtimeNanos)
    }

    @Test fun movingFixUsesTheRequestedSpeedAndBearingWithoutRandomPerturbation() {
        val moving = target.copy(speed = 60f / 3.6f, bearing = 123f, altitude = 120.0)
        val fix = MockFixFactory().create(MockProviderProfile.GPS, moving, 1000, 1_000_000_000)
        assertEquals(moving, fix.snapshot)
        assertEquals(3f, fix.accuracy, 0f)
    }

    @Test fun networkAndSatelliteProvidersKeepTheirDifferentCapabilities() {
        assertTrue(MockProviderProfile.GPS.requiresSatellite)
        assertFalse(MockProviderProfile.GPS.requiresNetwork)
        assertTrue(MockProviderProfile.GPS.highPower && MockProviderProfile.GPS.fineAccuracy)
        assertTrue(MockProviderProfile.NETWORK.requiresNetwork && MockProviderProfile.NETWORK.requiresCell)
        assertFalse(MockProviderProfile.NETWORK.requiresSatellite)
        assertFalse(MockProviderProfile.NETWORK.highPower || MockProviderProfile.NETWORK.fineAccuracy)
        assertTrue(MockProviderProfile.FUSED.requiresNetwork && MockProviderProfile.FUSED.requiresSatellite)
    }

    @Test fun invalidCoordinatesAndSpeedsAreRejectedBeforeAnyProviderWrite() {
        for (invalid in listOf(target.copy(latitude = Double.NaN), target.copy(longitude = 181.0),
            target.copy(speed = -1f), target.copy(altitude = Double.POSITIVE_INFINITY))) {
            try {
                MockFixFactory().create(MockProviderProfile.GPS, invalid, 1000, 1_000_000_000)
                fail("Invalid fix accepted")
            } catch (expected: IllegalArgumentException) { }
        }
    }
}
