package com.laozhang.mockgps.model

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class FavoriteKind(val label: String) { LOCATION("位置"), ROUTE("路线") }

data class RouteCoordinate(val latitude: Double, val longitude: Double) {
    fun isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun key() = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
}

data class FavoritePoint(
    val name: String, val latitude: Double, val longitude: Double,
    val city: String = "", val uid: String = ""
) {
    val coordinate get() = RouteCoordinate(latitude, longitude)
}

/** Values only: credentials and map SDK objects are never stored in a favorite. */
data class FavoriteEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: FavoriteKind,
    val name: String,
    val points: List<FavoritePoint>,
    val travelMode: String = TravelMode.DRIVING.name,
    val speedKmH: Int = 60,
    val path: List<RouteCoordinate> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val schemaVersion: Int = 1,
    val routeMetadata: RouteMetadata? = null,
    val navigationOptions: NavigationOptions? = null
) {
    fun validate() {
        require(FavoriteKind.values().any { it == kind }) { "收藏类型无效" }
        require(schemaVersion == 1) { "不支持此收藏数据版本" }
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "收藏数据标识无效" }
        require(name.trim().isNotEmpty() && name.length <= 40) { "名称请填写 1～40 个字" }
        require(points.isNotEmpty() && points.all { it.coordinate.isValid() }) { "收藏坐标无效" }
        require(points.all { it.name.length <= 512 && it.city.length <= 128 && it.uid.length <= 512 }) { "地点信息过长" }
        routeMetadata?.validate()
        navigationOptions?.validate()
        if (kind == FavoriteKind.LOCATION) {
            require(points.size == 1) { "位置收藏只能包含一个地点" }
        } else {
            require(points.size in 2..5) { "请设置起终点，最多添加 3 个途经点" }
            require(TravelMode.values().any { it.name == travelMode }) { "出行方式无效" }
            require(speedKmH in 1..500) { "速度请设置在 1～500 km/h" }
            require(path.size in 2..50_000) { "路线为空或过长，请重新规划或分段收藏" }
            require(path.all { it.isValid() } && path.any { it != path.first() }) { "收藏道路轨迹无效" }
        }
    }

    fun identity(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(kind.name.toByteArray(Charsets.UTF_8))
        points.forEach { digest.update(("|" + it.coordinate.key()).toByteArray(Charsets.UTF_8)) }
        if (kind == FavoriteKind.ROUTE) {
            digest.update(("|" + travelMode).toByteArray(Charsets.UTF_8))
            path.forEach { digest.update(("|" + it.key()).toByteArray(Charsets.UTF_8)) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matchesRoute(mode: TravelMode, routePoints: List<FavoritePoint>): Boolean =
        kind == FavoriteKind.ROUTE && travelMode == mode.name &&
            points.map { it.coordinate.key() } == routePoints.map { it.coordinate.key() }
}
