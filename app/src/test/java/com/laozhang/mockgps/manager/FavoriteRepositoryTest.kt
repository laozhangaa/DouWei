package com.laozhang.mockgps.manager

import com.google.gson.Gson
import com.laozhang.mockgps.model.*
import org.junit.Assert.*
import org.junit.Test

class FavoriteRepositoryTest {
    private class MemoryStorage : FavoriteStorage {
        val values = mutableMapOf<String, String>()
        var failIndexWrite = false
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) {
            if (failIndexWrite && key == "favorite_index_v1") error("disk full")
            values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
    }
    private val storage = MemoryStorage()
    private val repository = FavoriteRepository(storage)
    private fun location(name: String = "家", latitude: Double = 26.4) = FavoriteEntry(
        kind = FavoriteKind.LOCATION, name = name,
        points = listOf(FavoritePoint("原地址", latitude, 106.6)))
    private fun route(mode: TravelMode = TravelMode.CYCLING, middle: RouteCoordinate = RouteCoordinate(26.41, 106.61)) = FavoriteEntry(
        kind = FavoriteKind.ROUTE, name = "上班路线",
        points = listOf(FavoritePoint("起点", 26.4, 106.6), FavoritePoint("终点", 26.42, 106.62)),
        travelMode = mode.name, speedKmH = mode.defaultSpeedKmH,
        path = listOf(RouteCoordinate(26.4, 106.6), middle, RouteCoordinate(26.42, 106.62)))

    @Test fun moreThanTenFavoritesSurviveRepositoryRecreation() {
        repeat(25) { repository.save(location("地点$it", 26.0 + it * 0.01)) }
        val reloaded = FavoriteRepository(storage).all(FavoriteKind.LOCATION)
        assertEquals(25, reloaded.size)
        assertEquals(25, reloaded.map { it.points.first().latitude }.distinct().size)
    }

    @Test fun sameCoordinateUpdatesNameAndKeepsIdentityAndCreationTime() {
        val first = repository.save(location().copy(createdAt = 123L))
        val second = repository.save(location("  我的家  "))
        assertEquals(first.id, second.id)
        assertEquals(123L, second.createdAt)
        assertEquals("我的家", second.name)
        assertEquals(1, repository.all().size)
    }

    @Test fun sameEndpointsCanKeepDifferentRoadsAndModes() {
        repository.save(route())
        repository.save(route(middle = RouteCoordinate(26.415, 106.615)))
        repository.save(route(mode = TravelMode.WALKING))
        assertEquals(3, repository.all(FavoriteKind.ROUTE).size)
    }

    @Test fun completeRoadAndWaypointsSpeedAndModeSurviveRoundTrip() {
        val original = route().copy(points = route().points.toMutableList().apply {
            add(1, FavoritePoint("途经公园", 26.41, 106.61, "贵阳市", "park"))
        }, speedKmH = 21)
        val saved = repository.save(original)
        val restored = FavoriteRepository(storage).all().single()
        assertEquals(saved, restored)
        assertEquals(3, restored.path.size)
        assertTrue(restored.matchesRoute(TravelMode.CYCLING, original.points))
        assertFalse(restored.matchesRoute(TravelMode.WALKING, original.points))
        assertFalse(restored.matchesRoute(TravelMode.CYCLING, original.points.reversed()))
    }

    @Test fun renameAndDeleteLeaveOtherKindsAndEntriesUntouched() {
        val home = repository.save(location())
        val work = repository.save(location("公司", 27.0))
        val road = repository.save(route())
        repository.rename(home.id, "新家")
        assertEquals("新家", repository.all().single { it.id == home.id }.name)
        repository.delete(home.id)
        assertEquals(setOf(work, road), repository.all().toSet())
        assertFalse(storage.values.containsKey("entry_${home.id}"))
    }

    @Test fun savingSameRoadAgainUpdatesSpeedInsteadOfCreatingDuplicate() {
        val first = repository.save(route())
        val second = repository.save(route().copy(name = "慢骑", speedKmH = 12))
        assertEquals(first.id, second.id)
        assertEquals(12, repository.all().single().speedKmH)
    }

    @Test fun corruptEntryIsSkippedWhileValidFavoritesRemainAvailable() {
        val good = repository.save(location())
        val bad = repository.save(location("坏数据", 27.0))
        storage.values["entry_${bad.id}"] = "{bad json"
        assertEquals(listOf(good), repository.all())
        storage.values["entry_${bad.id}"] = Gson().toJson(bad).replace("\"LOCATION\"", "\"UNKNOWN\"")
        assertEquals(listOf(good), repository.all())
    }

    @Test fun entryWhoseStoredIdDoesNotMatchIndexIsIgnored() {
        val first = repository.save(location())
        storage.values["entry_${first.id}"] = Gson().toJson(location("别的数据"))
        assertTrue(repository.all().isEmpty())
    }

    @Test fun malformedOrMissingIndexDoesNotCrash() {
        assertTrue(repository.all().isEmpty())
        storage.values["favorite_index_v1"] = "{broken"
        assertTrue(repository.all().isEmpty())
    }

    @Test fun failedIndexWriteRollsBackNewEntryAndKeepsExistingFavorites() {
        val first = repository.save(location())
        storage.failIndexWrite = true
        assertThrows(IllegalStateException::class.java) { repository.save(location("公司", 27.0)) }
        assertEquals(listOf(first), repository.all())
        assertEquals(setOf("favorite_index_v1", "entry_${first.id}"), storage.values.keys)
    }

    @Test fun failedDeleteDoesNotRemoveEntryWhenIndexCannotBeWritten() {
        val first = repository.save(location())
        storage.failIndexWrite = true
        assertThrows(IllegalStateException::class.java) { repository.delete(first.id) }
        assertEquals(listOf(first), repository.all())
    }

    @Test fun invalidLocationAndNameAreRejectedWithoutWritingAnything() {
        assertThrows(IllegalArgumentException::class.java) { repository.save(location("   ")) }
        assertThrows(IllegalArgumentException::class.java) { repository.save(location(latitude = Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { repository.save(location(latitude = 91.0)) }
        assertThrows(IllegalArgumentException::class.java) { repository.save(location("x".repeat(41))) }
        assertTrue(storage.values.isEmpty())
    }

    @Test fun routeWithoutMovingRoadGeometryOrWithInvalidSpeedIsRejected() {
        val original = route()
        listOf(original.copy(path = emptyList()), original.copy(path = listOf(original.path.first())),
            original.copy(path = listOf(original.path.first(), original.path.first())),
            original.copy(speedKmH = 0), original.copy(speedKmH = 501),
            original.copy(travelMode = "UNKNOWN"), original.copy(points = original.points.take(1)),
            original.copy(path = listOf(RouteCoordinate(Double.NaN, 0.0), original.path.last()))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { repository.save(invalid) }
        }
        assertTrue(storage.values.isEmpty())
    }

    @Test fun missingLegacyTravelModeUsesDrivingAndRunningUsesPedestrianRoads() {
        assertEquals(TravelMode.DRIVING, TravelMode.fromId(null))
        assertEquals(TravelMode.DRIVING, TravelMode.fromId("unknown"))
        assertTrue(TravelMode.RUNNING.usesWalkingRoads)
        assertTrue(TravelMode.WALKING.usesWalkingRoads)
        assertFalse(TravelMode.CYCLING.usesWalkingRoads)
    }
}
