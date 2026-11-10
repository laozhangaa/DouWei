package com.laozhang.mockgps.server

import org.junit.Assert.*
import org.junit.Test

class MockProviderSessionTest {
    private class Backend(val names: List<String> = listOf("gps", "network", "fused")) :
        MockProviderSession.Backend<String> {
        val registered = linkedSetOf<String>()
        val fixes = linkedMapOf<String, String>()
        val enabled = linkedSetOf<String>()
        val enabledSnapshots = mutableListOf<Map<String, String>>()
        val registrations = mutableListOf<String>()
        val removals = mutableListOf<String>()
        var failure: ((String, String) -> Exception?)? = null

        private fun checkFailure(operation: String, provider: String) {
            failure?.invoke(operation, provider)?.let { throw it }
        }

        override fun providers() = names
        override fun register(provider: String) {
            checkFailure("register", provider)
            check(registered.add(provider)) { "Provider registered twice without cleanup" }
            registrations.add(provider)
            fixes.remove(provider)
        }
        override fun setEnabled(provider: String, enabled: Boolean) {
            checkFailure(if (enabled) "enable" else "disable", provider)
            if (enabled) {
                this.enabled.add(provider)
                enabledSnapshots.add(fixes.toMap())
            } else this.enabled.remove(provider)
        }
        override fun write(provider: String, location: String) {
            checkFailure("write", provider)
            check(provider in registered)
            fixes[provider] = location
        }
        override fun remove(provider: String) {
            removals.add(provider)
            checkFailure("remove", provider)
            check(registered.remove(provider)) { "Removing an unowned provider" }
            enabled.remove(provider)
            fixes.remove(provider)
        }
    }

    private fun expectFailure(block: () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected failure")
    }

    @Test fun allProvidersHaveTheNewTargetBeforeAnyIsEnabled() {
        val backend = Backend()
        MockProviderSession(backend).inject("target")
        assertEquals(backend.names.toSet(), backend.enabled)
        assertEquals(3, backend.enabledSnapshots.size)
        backend.enabledSnapshots.forEach { snapshot ->
            assertEquals(backend.names.associateWith { "target" }, snapshot)
        }
    }

    @Test fun continuousUpdatesDoNotRecreateEnabledProviders() {
        val backend = Backend()
        val session = MockProviderSession(backend)
        session.inject("first")
        session.inject("next")
        assertEquals(backend.names, backend.registrations)
        assertTrue(backend.removals.isEmpty())
        assertEquals(backend.names.associateWith { "next" }, backend.fixes)
    }

    @Test fun pauseThenResumeBuildsAFreshSessionWithTheNewTarget() {
        val backend = Backend()
        val session = MockProviderSession(backend)
        session.inject("old")
        session.close()
        assertTrue(backend.enabled.isEmpty())
        assertTrue(backend.registered.isEmpty())
        session.inject("new")
        assertEquals(backend.names + backend.names, backend.registrations)
        backend.enabledSnapshots.takeLast(3).forEach {
            assertEquals(backend.names.associateWith { "new" }, it)
        }
    }

    @Test fun registrationFailureCleansOnlySuccessfullyRegisteredProviders() {
        val backend = Backend()
        backend.failure = { operation, provider ->
            if (operation == "register" && provider == "network") SecurityException() else null
        }
        assertTrue(expectFailure { MockProviderSession(backend).inject("target") } is SecurityException)
        assertEquals(listOf("gps"), backend.removals)
        assertTrue(backend.registered.isEmpty())
        assertTrue(backend.enabledSnapshots.isEmpty())
    }

    @Test fun failedInitialWriteNeverEnablesAPartiallySeededSession() {
        val backend = Backend()
        backend.failure = { operation, provider ->
            if (operation == "write" && provider == "network") IllegalStateException() else null
        }
        expectFailure { MockProviderSession(backend).inject("target") }
        assertTrue(backend.enabledSnapshots.isEmpty())
        assertTrue(backend.registered.isEmpty())
        assertEquals(backend.names, backend.removals)
    }

    @Test fun enableFailureRollsBackEveryProvider() {
        val backend = Backend()
        backend.failure = { operation, provider ->
            if (operation == "enable" && provider == "network") IllegalStateException() else null
        }
        expectFailure { MockProviderSession(backend).inject("target") }
        assertTrue(backend.enabled.isEmpty())
        assertTrue(backend.registered.isEmpty())
        assertEquals(backend.names, backend.removals)
    }

    @Test fun failedLiveUpdateDoesNotLeaveOtherProvidersRunningAtDifferentTargets() {
        val backend = Backend()
        val session = MockProviderSession(backend)
        session.inject("old")
        backend.failure = { operation, provider ->
            if (operation == "write" && provider == "network") IllegalStateException() else null
        }
        expectFailure { session.inject("new") }
        assertTrue(backend.enabled.isEmpty())
        assertTrue(backend.registered.isEmpty())
    }

    @Test fun failedRemovalIsRetriedBeforeTheNextSessionRegistersAnything() {
        val backend = Backend()
        val session = MockProviderSession(backend)
        session.inject("old")
        backend.failure = { operation, provider ->
            if (operation == "remove" && provider == "gps") IllegalStateException() else null
        }
        expectFailure { session.close() }
        assertEquals(setOf("gps"), backend.registered)
        backend.failure = null
        session.inject("new")
        assertEquals(backend.names + "gps", backend.removals)
        assertEquals(backend.names.associateWith { "new" }, backend.fixes)
    }

    @Test fun rollbackFailureDoesNotHideTheOriginalWriteFailure() {
        val backend = Backend()
        val original = SecurityException("location write")
        backend.failure = { operation, provider ->
            when {
                operation == "write" -> original
                operation == "remove" && provider == "gps" -> IllegalStateException("cleanup")
                else -> null
            }
        }
        val failure = expectFailure { MockProviderSession(backend).inject("target") }
        assertSame(original, failure)
        assertEquals("cleanup", failure.suppressed.single().message)
        assertEquals(setOf("gps"), backend.registered)
    }

    @Test fun devicesWithoutFusedOnlyRegisterTheAvailableProviders() {
        val backend = Backend(listOf("gps", "network"))
        val session = MockProviderSession(backend)
        session.inject("target")
        session.close()
        session.close()
        assertEquals(backend.names, backend.registrations)
        assertEquals(backend.names, backend.removals)
    }

    @Test fun anEmptyProviderListCannotReportASuccessfulInjection() {
        val backend = Backend(emptyList())
        assertTrue(expectFailure { MockProviderSession(backend).inject("target") } is IllegalStateException)
        assertTrue(backend.registrations.isEmpty())
    }
}
