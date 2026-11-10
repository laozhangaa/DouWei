package com.laozhang.mockgps.model

/** A GCJ-02 display position, published only after a successful system injection. */
data class SimulationLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val bearing: Float
)
