package com.laozhang.mockgps.server

/** Owns only the test providers successfully registered by this session. */
internal class MockProviderSession<T>(private val backend: Backend<T>) {
    interface Backend<T> {
        fun providers(): List<String>
        fun register(provider: String)
        fun setEnabled(provider: String, enabled: Boolean)
        fun write(provider: String, location: T)
        fun remove(provider: String)
    }

    private val registered = linkedSetOf<String>()
    private var ready = false

    fun inject(location: T) {
        try {
            if (!ready) {
                // Resolve a previous cleanup failure before replacing any provider.
                close()
                val providers = backend.providers().distinct()
                check(providers.isNotEmpty()) { "No mock location providers available" }
                for (provider in providers) {
                    backend.register(provider)
                    registered.add(provider)
                    backend.setEnabled(provider, false)
                }
                // Give every provider a first fix before announcing it as enabled.
                for (provider in registered) backend.write(provider, location)
                for (provider in registered) backend.setEnabled(provider, true)
                ready = true
            }
            // Update normal listeners and last-location caches after enabling, too.
            for (provider in registered) backend.write(provider, location)
        } catch (error: Exception) {
            try {
                close()
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    fun close() {
        ready = false
        var failure: Exception? = null
        for (provider in registered.toList()) {
            try {
                backend.remove(provider)
                registered.remove(provider)
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        // Retain failed removals for the next cleanup attempt.
        failure?.let { throw it }
    }
}
