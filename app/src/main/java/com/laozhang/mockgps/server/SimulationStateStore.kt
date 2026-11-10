package com.laozhang.mockgps.server

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.laozhang.mockgps.model.SimulationState
import com.laozhang.mockgps.model.SimulationLocation
import com.laozhang.mockgps.model.NavigationProgress

/** Published by GpsService on the main thread; screens only observe it. */
object SimulationStateStore {
    private val mutableState = MutableLiveData(SimulationState())
    private val mutableLocation = MutableLiveData<SimulationLocation?>(null)
    private val mutableProgress = MutableLiveData<NavigationProgress?>(null)
    val state: LiveData<SimulationState> = mutableState
    val location: LiveData<SimulationLocation?> = mutableLocation
    val progress: LiveData<NavigationProgress?> = mutableProgress
    val current: SimulationState get() = mutableState.value ?: SimulationState()

    internal fun publish(state: SimulationState) {
        if (!state.hasSession) clearLocation()
        if (!state.hasSession) mutableProgress.value = null
        if (state != current) mutableState.value = state
    }

    internal fun publishLocation(location: SimulationLocation) {
        if (current.hasSession) mutableLocation.value = location
    }

    internal fun clearLocation() {
        if (mutableLocation.value != null) mutableLocation.value = null
    }
    internal fun publishProgress(progress: NavigationProgress?) { mutableProgress.value = progress }
}
