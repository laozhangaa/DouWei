package com.laozhang.mockgps.server

/** Lightweight heartbeat, independent of UI/map rendering and fixed-coordinate change detection. */
object InjectionDiagnostics {
    @Volatile var lastAcceptedNanos: Long = 0
        private set
    @Volatile var lastIntervalMillis: Long = 0
        private set
    @Volatile var providerCount: Int = 0
        internal set
    @Volatile var lastError: String? = null
        internal set
    @Volatile var active: Boolean = false
        internal set

    internal fun accepted(nowNanos: Long) {
        if (lastAcceptedNanos > 0) lastIntervalMillis = (nowNanos - lastAcceptedNanos) / 1_000_000L
        lastAcceptedNanos = nowNanos
    }

    internal fun starting() {
        active = true; lastAcceptedNanos = 0; lastIntervalMillis = 0; lastError = null
    }
}
