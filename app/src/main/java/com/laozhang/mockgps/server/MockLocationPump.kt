package com.laozhang.mockgps.server

import java.util.concurrent.atomic.AtomicLong

/** Serializes all provider operations on a worker; route playback only supplies snapshots. */
internal class MockLocationPump<T : Any>(
    private val scheduler: Scheduler,
    private val session: MockProviderSession<T>,
    private val onInjected: (Long, T) -> Unit,
    private val onFailure: (Long, Exception) -> Unit,
    private val onCleanupFailure: (Exception) -> Unit,
    private val intervalMillis: Long = 100L,
    private val onHeartbeat: (Long) -> Unit = {}
) {
    interface Scheduler {
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMillis: Long)
        fun remove(task: Runnable)
    }

    private val generation = AtomicLong()
    // The fields below belong exclusively to the scheduler's worker thread.
    private var latest: T? = null
    private var source: (() -> T)? = null
    private var reported: T? = null
    private var pending: Runnable? = null

    init { require(intervalMillis > 0) }

    fun start(value: T, source: (() -> T)? = null): Long {
        val ticket = generation.incrementAndGet()
        scheduler.post(Runnable {
            if (generation.get() != ticket) return@Runnable
            cancelTick()
            try {
                session.close()
                latest = value
                this.source = source
                reported = null
                inject(ticket)
            } catch (error: Exception) {
                fail(ticket, error)
            }
        })
        return ticket
    }

    fun update(value: T) {
        val ticket = generation.get()
        scheduler.post(Runnable {
            if (generation.get() == ticket && latest != null) {
                source = null
                latest = value
            }
        })
    }

    /** Invalidates queued/in-flight results immediately, then releases providers on their worker. */
    fun stop(): Long {
        val ticket = generation.incrementAndGet()
        scheduler.post(Runnable {
            cancelTick()
            latest = null
            source = null
            reported = null
            try { session.close() } catch (error: Exception) { onCleanupFailure(error) }
        })
        return ticket
    }

    private fun inject(ticket: Long) {
        if (generation.get() != ticket) return
        try {
            val value = source?.invoke() ?: latest ?: return
            if (generation.get() != ticket) return
            session.inject(value)
            if (generation.get() != ticket) return
            onHeartbeat(ticket)
            // Repeated fixes need new timestamps, but unchanged UI snapshots need no callbacks.
            if (reported != value) {
                reported = value
                onInjected(ticket, value)
            }
            val next = Runnable { inject(ticket) }
            pending = next
            scheduler.postDelayed(next, intervalMillis)
        } catch (error: Exception) {
            fail(ticket, error)
        }
    }

    private fun fail(ticket: Long, error: Exception) {
        cancelTick()
        latest = null
        source = null
        try { session.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        if (generation.get() == ticket) onFailure(ticket, error)
    }

    private fun cancelTick() {
        pending?.let(scheduler::remove)
        pending = null
    }
}
