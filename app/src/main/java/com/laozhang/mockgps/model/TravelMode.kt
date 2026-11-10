package com.laozhang.mockgps.model

/** Running shares pedestrian roads, with its own playback speed. */
enum class TravelMode(val label: String, val defaultSpeedKmH: Int) {
    DRIVING("驾车", 60), CYCLING("骑行", 18), WALKING("步行", 5), RUNNING("跑步", 10);

    val usesWalkingRoads get() = this == WALKING || this == RUNNING

    companion object {
        fun fromId(id: String?) = values().firstOrNull { it.name == id } ?: DRIVING
    }
}
