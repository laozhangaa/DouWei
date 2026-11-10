package com.laozhang.mockgps.server

import org.junit.Assert.*
import org.junit.Test

class SnapshotDeliveryTest {
    @Test fun terminalFixReachesUiEvenWhenItIsTheOnlyCallbackBeforeNextThrottleWindow() {
        val gate = SnapshotDeliveryGate()
        val delivered = arrayListOf<String>()
        // The pump emits changed values only, so there will be no retry after "finished".
        listOf(0L to "moving", 100_000_000L to "moving-again", 150_000_000L to "finished").forEach { (now, value) ->
            if (gate.accept(1L, now, value == "finished")) delivered.add(value)
        }
        assertEquals(listOf("moving", "finished"), delivered)
    }

    @Test fun rapidRestartAndOrdinaryUpdatesKeepFirstAndPeriodicFixes() {
        val gate = SnapshotDeliveryGate()
        assertTrue(gate.accept(1, 0, false))
        assertFalse(gate.accept(1, 100_000_000L, false))
        assertTrue(gate.accept(2, 100_000_001L, false))
        assertFalse(gate.accept(2, 200_000_000L, false))
        assertTrue(gate.accept(2, 300_000_001L, false))
    }
}
