package com.laozhang.mockgps.model

import org.junit.Assert.*
import org.junit.Test

class SimulationStateTest {
    @Test fun startRequiresSuccessfulLocationInjectionBeforeRunning() {
        val starting = SimulationState().start(NaviType.LOCATION, "目标地址")
        assertEquals(SimulationStatus.STARTING, starting.status)
        assertTrue(starting.hasSession)
        val running = starting.locationInjected()
        assertEquals(SimulationStatus.RUNNING, running.status)
        assertEquals("目标地址", running.target)
    }

    @Test fun pauseIgnoresLateLocationSuccess() {
        val paused = SimulationState().start(NaviType.NAVI, "起点 → 终点")
            .locationInjected().pause()
        assertEquals(SimulationStatus.PAUSED, paused.locationInjected().status)
        assertTrue(paused.hasSession)
    }

    @Test fun resumeRetainsSessionAndWaitsForAnotherSuccessfulInjection() {
        val paused = SimulationState().start(NaviType.NAVI_FILE, "文件路线")
            .locationInjected().pause()
        val resuming = paused.resume()
        assertEquals(SimulationStatus.STARTING, resuming.status)
        assertEquals(NaviType.NAVI_FILE, resuming.mode)
        assertEquals(paused.target, resuming.target)
        assertEquals(SimulationStatus.RUNNING, resuming.locationInjected().status)
    }

    @Test fun failedSessionCannotBeRevivedByLateSuccessOrResume() {
        val failed = SimulationState().start(NaviType.LOCATION, "目标")
            .fail("模拟定位权限已失效")
        assertEquals(SimulationStatus.ERROR, failed.locationInjected().resume().pause().status)
        assertEquals("模拟定位权限已失效", failed.error)
        assertFalse(failed.hasSession)
        assertNull(failed.start(NaviType.LOCATION, "重新选择的目标").error)
    }

    @Test fun stopClearsSessionAndLateCallbacksCannotRestartIt() {
        val stopped = SimulationState().start(NaviType.NAVI, "路线")
            .locationInjected().pause().stop()
        assertEquals(SimulationState(), stopped)
        assertFalse(stopped.hasSession)
        assertEquals(SimulationStatus.STOPPED, stopped.locationInjected().resume().pause().status)
    }

    @Test fun cancellingWhileStartingDoesNotLaterBecomeRunning() {
        val cancelled = SimulationState().start(NaviType.LOCATION, "目标").pause()
        assertEquals(SimulationStatus.PAUSED, cancelled.locationInjected().status)
        assertEquals(SimulationStatus.STOPPED, SimulationState().status)
    }
}
