package com.laozhang.mockgps.server

import com.laozhang.mockgps.model.RouteCoordinate
import com.laozhang.mockgps.model.NavigationProgress
import kotlin.math.max

/** Immutable WGS-84 fix plus the GCJ-02 point used by our maps. No UI objects cross threads. */
internal data class MockLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val displayLatitude: Double,
    val displayLongitude: Double,
    val speed: Float,
    val bearing: Float,
    val altitude: Double = 0.0,
    val routeIndex: Int? = null,
    val routeSize: Int? = null,
    val routePoint: RouteCoordinate? = null,
    val progress: NavigationProgress? = null
)

internal enum class MockProviderProfile(
    val provider: String,
    val requiresNetwork: Boolean,
    val requiresSatellite: Boolean,
    val requiresCell: Boolean,
    val hasCost: Boolean,
    val highPower: Boolean,
    val fineAccuracy: Boolean
) {
    GPS("gps", false, true, false, false, true, true),
    NETWORK("network", true, false, true, true, false, false),
    FUSED("fused", true, true, true, false, true, true);

    companion object {
        fun forProvider(provider: String) = values().first { it.provider == provider }
    }
}

internal data class MockFix(
    val snapshot: MockLocationSnapshot,
    val accuracy: Float,
    val verticalAccuracy: Float,
    val speedAccuracy: Float,
    val bearingAccuracy: Float,
    val satellites: Int?,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long
)

/** Called only on the location worker. Each provider has its own monotonic clock. */
internal class MockFixFactory {
    private val previous = mutableMapOf<MockProviderProfile, Pair<Long, Long>>()

    fun create(
        profile: MockProviderProfile,
        snapshot: MockLocationSnapshot,
        wallTimeMillis: Long,
        elapsedRealtimeNanos: Long
    ): MockFix {
        require(snapshot.latitude.isFinite() && snapshot.latitude in -90.0..90.0)
        require(snapshot.longitude.isFinite() && snapshot.longitude in -180.0..180.0)
        require(snapshot.altitude.isFinite() && snapshot.bearing.isFinite())
        require(snapshot.speed.isFinite() && snapshot.speed >= 0)
        val network = profile == MockProviderProfile.NETWORK
        val offsetMillis = if (network) 50L else 0L
        val last = previous[profile] ?: (0L to 0L)
        val time = max(wallTimeMillis - offsetMillis, last.first + 1)
        val elapsed = max(elapsedRealtimeNanos - offsetMillis * 1_000_000L, last.second + 1)
        previous[profile] = time to elapsed
        return MockFix(
            snapshot = snapshot,
            accuracy = if (network) 50f else 3f,
            verticalAccuracy = if (network) 50f else 5f,
            speedAccuracy = if (network) 2f else 1f,
            bearingAccuracy = if (network) 30f else 5f,
            satellites = if (network) null else 8,
            timeMillis = time,
            elapsedRealtimeNanos = elapsed
        )
    }
}
