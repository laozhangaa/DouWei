package com.laozhang.mockgps.server

/** A terminal snapshot may be emitted only once by the pump, so it must bypass UI throttling. */
internal class SnapshotDeliveryGate(private val intervalNanos: Long = 200_000_000L) {
    private var lastTicket = -1L
    private var lastNanos = 0L
    fun accept(ticket: Long, now: Long, terminal: Boolean): Boolean {
        if (ticket == lastTicket && !terminal && now - lastNanos < intervalNanos) return false
        lastTicket = ticket
        lastNanos = now
        return true
    }
}
