package com.laozhang.mockgps.model

enum class SimulationStatus { STOPPED, STARTING, RUNNING, PAUSED, ERROR }

/** Runtime state only: a new process must never restore a stale "running" flag. */
data class SimulationState(
    val status: SimulationStatus = SimulationStatus.STOPPED,
    val mode: Int = NaviType.NONE,
    val target: String = "",
    val error: String? = null
) {
    val hasSession: Boolean
        get() = status == SimulationStatus.STARTING ||
                status == SimulationStatus.RUNNING || status == SimulationStatus.PAUSED

    fun start(mode: Int, target: String) =
        SimulationState(SimulationStatus.STARTING, mode, target)

    fun locationInjected(): SimulationState =
        if (status == SimulationStatus.STARTING) copy(status = SimulationStatus.RUNNING) else this

    fun pause(): SimulationState =
        if (status == SimulationStatus.RUNNING || status == SimulationStatus.STARTING)
            copy(status = SimulationStatus.PAUSED) else this

    fun resume(): SimulationState =
        if (status == SimulationStatus.PAUSED) copy(status = SimulationStatus.STARTING) else this

    fun fail(message: String) = copy(status = SimulationStatus.ERROR, error = message)

    fun stop() = SimulationState()
}
