package com.laozhang.mockgps.model

enum class DrivingPreference(val label: String, val amapOnly: Boolean = false) {
    RECOMMENDED("推荐路线"), AVOID_CONGESTION("躲避拥堵"), LESS_TOLL("少收费"),
    TIME_FIRST("时间优先"), AVOID_HIGHWAY("不走高速", true), HIGHWAY_FIRST("高速优先", true);

    companion object {
        fun fromId(value: String?) = values().firstOrNull { it.name == value } ?: RECOMMENDED
    }
}

/** The selected speed is a ceiling in realistic mode. No live traffic-signal phase is claimed. */
data class NavigationOptions(
    val preference: String = DrivingPreference.RECOMMENDED.name,
    val realistic: Boolean = false,
    val simulateStops: Boolean = false,
    val stopSeconds: Int = 20
) {
    fun validate() {
        require(DrivingPreference.values().any { it.name == preference }) { "路线偏好无效" }
        require(stopSeconds in 5..120) { "停车时间请设置在 5～120 秒" }
        require(!simulateStops || realistic) { "请先开启真实行驶模式" }
    }
    val drivingPreference get() = DrivingPreference.fromId(preference)
}

data class SimulatedStop(val distanceMeters: Double, val seconds: Int = 20) {
    fun validate() {
        require(distanceMeters.isFinite() && distanceMeters >= 0 && seconds in 5..120) { "模拟停车点无效" }
    }
}

data class RouteMetadata(
    val provider: String = "UNKNOWN",
    val coordinateSystem: String = "gcj02",
    val preference: String = DrivingPreference.RECOMMENDED.name,
    val trafficLightCount: Int? = null,
    val roadNames: List<String> = emptyList(),
    val estimatedStopMeters: List<Double> = emptyList(),
    val sdkDurationSeconds: Long = 0,
    val tollYuan: Float? = null
) {
    fun validate() {
        require(provider in listOf("AMAP", "BAIDU", "UNKNOWN", "FILE")) { "地图来源无效" }
        require(coordinateSystem in listOf("gcj02", "gps84", "bd09")) { "路线坐标系无效" }
        require(trafficLightCount == null || trafficLightCount in 0..10_000) { "红绿灯数量无效" }
        require(roadNames.size <= 100 && roadNames.all { it.length <= 200 }) { "道路名称无效" }
        require(estimatedStopMeters.size <= 10_000 && estimatedStopMeters.all { it.isFinite() && it >= 0 }) { "停车点无效" }
        require(DrivingPreference.values().any { it.name == preference }) { "路线偏好无效" }
        require(sdkDurationSeconds in 0..31_536_000 && (tollYuan == null || tollYuan.isFinite() && tollYuan >= 0)) { "路线费用或时间无效" }
    }
}

data class PlaybackPosition(
    val distanceMeters: Double = 0.0,
    val completedStops: Int = 0,
    val stopRemainingSeconds: Double = 0.0
) {
    fun validate() {
        require(distanceMeters.isFinite() && distanceMeters >= 0 && completedStops in 0..10_000 &&
            stopRemainingSeconds.isFinite() && stopRemainingSeconds in 0.0..120.0) { "导航恢复进度无效" }
    }
}

data class NavigationProgress(
    val traveledMeters: Double, val totalMeters: Double, val speedKmH: Float,
    val remainingSeconds: Long, val stopRemainingSeconds: Int = 0, val finished: Boolean = false
)
