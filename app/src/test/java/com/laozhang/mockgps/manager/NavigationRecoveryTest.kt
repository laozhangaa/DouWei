package com.laozhang.mockgps.manager

import com.laozhang.mockgps.model.*
import org.junit.Assert.*
import org.junit.Test

class NavigationRecoveryTest {
    private class Storage : RecoveryStorage {
        val data = mutableMapOf<String, String>()
        val writes = mutableListOf<String>()
        override fun read(name: String) = data[name]
        override fun write(name: String, value: String) { data[name] = value; writes.add(name) }
        override fun remove(name: String) { data.remove(name) }
    }
    private fun route() = RecoveryRoute(target = "家 → 公司", points = emptyList(),
        path = listOf(RouteCoordinate(26.0, 106.0), RouteCoordinate(26.01, 106.01)),
        travelMode = "DRIVING", speedKmH = 120, metadata = RouteMetadata(provider = "AMAP"), options = NavigationOptions())

    @Test fun checkpointWritesOnlyCursorAndSurvivesRepositoryRecreation() {
        val storage = Storage(); val repository = NavigationRecoveryRepository(storage); val route = route()
        repository.start(route)
        val cursor = PlaybackPosition(123.45)
        repository.progress(route.id, cursor)
        assertEquals(listOf("route", "progress", "progress"), storage.writes)
        val restored = NavigationRecoveryRepository(storage).load()!!
        assertEquals(route, restored.route); assertEquals(cursor, restored.position)
        repository.clear(); assertNull(repository.load())
    }

    @Test fun oldCursorCannotAttachToANewRoute() {
        val storage = Storage(); val repository = NavigationRecoveryRepository(storage)
        val first = route(); repository.start(first); repository.progress(first.id, PlaybackPosition(500.0))
        val old = storage.data["progress"]!!
        val second = route(); repository.start(second); storage.data["progress"] = old
        assertEquals(second, repository.load()!!.route)
        assertEquals(PlaybackPosition(), repository.load()!!.position)
    }

    @Test fun malformedFutureAndMissingRecoveryDataFailWithoutStartingAnything() {
        val storage = Storage(); val repository = NavigationRecoveryRepository(storage)
        repository.start(route()); val original = storage.data["route"]!!
        listOf("null", "{}", "{broken", original.replace("\"version\":1", "\"version\":2"),
            original.replace("\"options\":{", "\"options\":null,\"unused\":{")).forEach {
            storage.data["route"] = it
            assertThrows(Exception::class.java) { repository.load() }
        }
        storage.data["route"] = original
        storage.data["progress"] = "{broken"
        assertThrows(Exception::class.java) { repository.load() }
    }
}
