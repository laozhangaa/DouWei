package com.laozhang.mockgps.server

import org.junit.Assert.*
import org.junit.Test

class MockLocationPumpTest {
    private class Scheduler : MockLocationPump.Scheduler {
        data class Task(val time: Long, val order: Int, val action: Runnable)
        var now = 0L
        private var sequence = 0
        private val tasks = mutableListOf<Task>()
        override fun post(task: Runnable) = postDelayed(task, 0)
        override fun postDelayed(task: Runnable, delayMillis: Long) {
            tasks.add(Task(now + delayMillis, sequence++, task))
        }
        override fun remove(task: Runnable) { tasks.removeAll { it.action === task } }
        fun advanceTo(time: Long) {
            while (true) {
                val next = tasks.minWithOrNull(compareBy<Task> { it.time }.thenBy { it.order }) ?: break
                if (next.time > time) break
                tasks.remove(next)
                now = next.time
                next.action.run()
            }
            now = time
        }
        fun flush() = advanceTo(now)
    }

    private class Fixture {
        val scheduler = Scheduler()
        val writes = mutableListOf<Triple<Long, String, String>>()
        val reported = mutableListOf<Pair<Long, String>>()
        val failures = mutableListOf<Pair<Long, Exception>>()
        val cleanupFailures = mutableListOf<Exception>()
        val heartbeats = mutableListOf<Long>()
        val providers = linkedSetOf<String>()
        var failProvider: String? = null
        var afterWrite: (() -> Unit)? = null
        val pump = MockLocationPump(scheduler, MockProviderSession(object : MockProviderSession.Backend<String> {
            override fun providers() = listOf("gps", "network", "fused")
            override fun register(provider: String) { check(providers.add(provider)) }
            override fun setEnabled(provider: String, enabled: Boolean) {}
            override fun write(provider: String, location: String) {
                if (provider == failProvider) throw SecurityException("permission revoked")
                writes.add(Triple(scheduler.now, provider, location))
                afterWrite?.invoke()
            }
            override fun remove(provider: String) { providers.remove(provider) }
        }), onInjected = { ticket, value -> reported.add(ticket to value) },
            onFailure = { ticket, error -> failures.add(ticket to error) },
            onCleanupFailure = { cleanupFailures.add(it) }, onHeartbeat = { heartbeats.add(it) })
    }

    @Test fun stationaryTargetIsReportedEvery100msWithoutRepeatingUiWork() {
        val f = Fixture()
        val ticket = f.pump.start("target")
        f.scheduler.advanceTo(1000)
        // Each provider gets a startup seed and a normal update at t=0, then ten ticks.
        for (provider in f.providers) {
            assertEquals(listOf(0L, 0L) + (1L..10L).map { it * 100 },
                f.writes.filter { it.second == provider }.map { it.first })
        }
        assertEquals(listOf(ticket to "target"), f.reported)
        assertEquals(List(11) { ticket }, f.heartbeats)
    }

    @Test fun repeatedInjectionDoesNotAdvancePlaybackOrVibrationTargets() {
        val f = Fixture()
        f.pump.start("route-start")
        f.scheduler.advanceTo(999)
        assertTrue(f.writes.all { it.third == "route-start" })
        f.pump.update("one-second-route-point")
        f.scheduler.advanceTo(1999)
        assertEquals(30, f.writes.count { it.first in 1000L..1999L })
        assertTrue(f.writes.filter { it.first in 1000L..1999L }.all { it.third == "one-second-route-point" })
        assertEquals(listOf("route-start", "one-second-route-point"), f.reported.map { it.second })
    }

    @Test fun stoppingBeforeStartupCancelsEveryWrite() {
        val f = Fixture()
        f.pump.start("cancelled")
        f.pump.stop()
        f.scheduler.advanceTo(2000)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.reported.isEmpty())
        assertTrue(f.providers.isEmpty())
        assertTrue(f.heartbeats.isEmpty())
    }

    @Test fun pauseResumeDoesNotReplayOldCoordinatesOrQueuedUpdates() {
        val f = Fixture()
        f.pump.start("old")
        f.scheduler.flush()
        f.pump.update("stale queued update")
        f.pump.stop()
        val ticket = f.pump.start("new")
        f.writes.clear()
        f.reported.clear()
        f.scheduler.advanceTo(500)
        assertTrue(f.writes.isNotEmpty())
        assertTrue(f.writes.all { it.third == "new" })
        assertEquals(listOf(ticket to "new"), f.reported)
    }

    @Test fun stoppingDuringAnInflightWriteSuppressesItsUiResultAndFurtherTicks() {
        val f = Fixture()
        var interrupted = false
        f.afterWrite = {
            if (!interrupted) {
                interrupted = true
                f.pump.stop()
            }
        }
        f.pump.start("target")
        f.scheduler.advanceTo(1000)
        assertTrue(f.reported.isEmpty())
        assertTrue(f.providers.isEmpty())
        assertTrue(f.writes.all { it.first == 0L })
    }

    @Test fun permissionLossClearsAllProvidersAndStopsUntilExplicitResume() {
        val f = Fixture()
        val first = f.pump.start("target")
        f.scheduler.flush()
        f.failProvider = "network"
        f.scheduler.advanceTo(1000)
        assertEquals(first, f.failures.single().first)
        assertTrue(f.failures.single().second is SecurityException)
        assertTrue(f.providers.isEmpty())
        assertTrue(f.writes.none { it.first > 100 })
        f.failProvider = null
        val resumed = f.pump.start("restored")
        f.scheduler.advanceTo(1100)
        assertEquals(resumed to "restored", f.reported.last())
        assertEquals(setOf("gps", "network", "fused"), f.providers)
    }

    @Test fun rapidTargetSwitchesOnlyStartTheLatestSession() {
        val f = Fixture()
        f.pump.start("first")
        f.pump.start("second")
        val ticket = f.pump.start("last")
        f.scheduler.advanceTo(300)
        assertTrue(f.writes.all { it.third == "last" })
        assertEquals(listOf(ticket to "last"), f.reported)
    }

    @Test fun aStoppedSessionRejectsLaterTargetUpdates() {
        val f = Fixture()
        f.pump.start("target")
        f.scheduler.flush()
        f.pump.stop()
        f.pump.update("late")
        f.scheduler.advanceTo(1000)
        assertTrue(f.writes.none { it.third == "late" || it.first > 0 })
        assertTrue(f.providers.isEmpty())
        assertTrue(f.cleanupFailures.isEmpty())
    }

    @Test fun routeSourceProducesANewSharedPositionForAllProvidersOnEveryTick() {
        val f = Fixture()
        f.pump.start("seed") { "route-${f.scheduler.now}" }
        f.scheduler.advanceTo(1000)
        assertEquals((0L..1000L step 100).map { "route-$it" }, f.reported.map { it.second })
        assertTrue(f.writes.all { it.third == "route-${it.first}" })
    }

    @Test fun movingSourceIsDiscardedWhenSwitchingToAFixedPosition() {
        val f = Fixture()
        var calls = 0
        f.pump.start("seed") { "route-${++calls}" }
        f.scheduler.advanceTo(100)
        f.pump.update("fixed")
        f.scheduler.advanceTo(1000)
        assertEquals(2, calls)
        assertTrue(f.writes.filter { it.first >= 200 }.all { it.third == "fixed" })
    }

    @Test fun stoppedSourcesCannotRunOrLeakIntoTheNextSession() {
        val f = Fixture()
        var calls = 0
        f.pump.start("seed") { calls++; "old" }
        f.pump.stop()
        f.pump.start("new")
        f.scheduler.advanceTo(1000)
        assertEquals(0, calls)
        assertTrue(f.writes.all { it.third == "new" })
    }

    @Test fun routeComputationFailureStopsAndCleansUpInsteadOfRepeatingTheLastFix() {
        val f = Fixture()
        f.pump.start("seed") {
            if (f.scheduler.now >= 100) throw IllegalArgumentException("bad geometry")
            "start"
        }
        f.scheduler.advanceTo(1000)
        assertEquals(1, f.failures.size)
        assertTrue(f.providers.isEmpty())
        assertTrue(f.writes.none { it.first >= 100 })
    }

    @Test fun stoppingDuringSourceComputationPreventsProviderRegistration() {
        val f = Fixture()
        f.pump.start("seed") { f.pump.stop(); "cancelled" }
        f.scheduler.advanceTo(1000)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.reported.isEmpty())
        assertTrue(f.providers.isEmpty())
    }
}
