package com.laozhang.mockgps.model

import java.util.UUID

data class RecoveryRoute(
    val id: String = UUID.randomUUID().toString(),
    val version: Int = 1,
    val target: String,
    val points: List<FavoritePoint>,
    val path: List<RouteCoordinate>,
    val travelMode: String,
    val speedKmH: Int,
    val metadata: RouteMetadata,
    val options: NavigationOptions,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun validate() {
        require(version == 1 && id.matches(Regex("[a-fA-F0-9-]{36}"))) { "不支持此导航恢复记录" }
        require(target.length <= 1100 && target.isNotBlank()) { "恢复路线名称无效" }
        require(points.size <= 5 && points.all { it.coordinate.isValid() && it.name.length <= 512 }) { "恢复地点无效" }
        require(path.size in 2..50_000 && path.all { it.isValid() } && path.any { it != path.first() }) { "恢复道路轨迹无效" }
        require(TravelMode.values().any { it.name == travelMode } && speedKmH in 1..500) { "恢复行驶设置无效" }
        metadata.validate()
        options.validate()
    }
}

data class NavigationRecovery(val route: RecoveryRoute, val position: PlaybackPosition = PlaybackPosition(),
                              val savedAt: Long = System.currentTimeMillis())
