package com.laozhang.mockgps.manager

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.laozhang.mockgps.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class FavoriteBackupTest {
    private class Storage : FavoriteStorage {
        val values = linkedMapOf<String, String>()
        var failAt: String? = null
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) {
            if (key == failAt) { failAt = null; error("disk full") }
            values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
    }
    private fun location(lat: Double = 26.0) = FavoriteEntry(kind = FavoriteKind.LOCATION,
        name = "家", points = listOf(FavoritePoint("家", lat, 106.0)))
    private fun route() = FavoriteEntry(kind = FavoriteKind.ROUTE, name = "通勤",
        points = listOf(FavoritePoint("家", 26.0, 106.0), FavoritePoint("公司", 26.01, 106.01)),
        path = listOf(RouteCoordinate(26.0, 106.0), RouteCoordinate(26.01, 106.01)),
        routeMetadata = RouteMetadata(provider = "BAIDU", preference = "LESS_TOLL", trafficLightCount = 3,
            roadNames = listOf("人民路"), estimatedStopMeters = listOf(100.0), tollYuan = 5f),
        navigationOptions = NavigationOptions("LESS_TOLL", true, true, 10))

    @Test fun completeBackupRoundTripPreservesRoadsLightsAndOptionsWithoutSettings() {
        val entries = listOf(location().copy(routeMetadata = RouteMetadata()), route())
        val text = FavoriteBackupCodec.encode(entries)
        assertEquals(entries, FavoriteBackupCodec.decode(text))
        val root = JsonParser().parse(text).asJsonObject
        assertEquals(setOf("format", "version", "exportedAt", "favorites"), root.keySet())
        assertFalse(text.contains("AMAP_KEY")); assertFalse(text.contains("BAIDU_KEY"))
    }

    @Test fun legacyFavoritesLoadAndExportWithoutLosingCoordinates() {
        val original = location()
        val decoded = Gson().fromJson(Gson().toJson(original), FavoriteEntry::class.java)
        decoded.validate()
        assertNull(decoded.routeMetadata); assertNull(decoded.navigationOptions)
        val backup = FavoriteBackupCodec.decode(FavoriteBackupCodec.encode(listOf(decoded))).single()
        assertEquals(original.points, backup.points)
        assertEquals("gcj02", backup.routeMetadata?.coordinateSystem)
    }

    @Test fun malformedNullFutureVersionAndBadCoordinatesNeverEnterRepository() {
        val valid = FavoriteBackupCodec.encode(listOf(location()))
        listOf("{broken", "null", "[]", "{}", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":1.5"),
            valid.replace("26.0", "91.0"), valid.replace("26.0", "NaN"),
            valid.replace("\"latitude\":26.0,", ""),
            valid.replace("\"points\":[", "\"points\":null,\"unused\":["),
            valid.replace("\"favorites\":[{", "\"favorites\":[null,{"),
            FavoriteBackupCodec.encode(listOf(route().copy(routeMetadata = RouteMetadata(coordinateSystem = "bd09")))))
            .forEach { text -> assertThrows(IllegalArgumentException::class.java) { FavoriteBackupCodec.decode(text) } }
    }

    @Test fun boundedReadRejectsOversizedStreamsAndAcceptsUtf8() {
        val valid = FavoriteBackupCodec.encode(listOf(location()))
        assertEquals(valid, FavoriteBackupCodec.read(valid.byteInputStream()))
        val endless = object : InputStream() {
            override fun read() = 32
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(32, offset, offset + length); return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { FavoriteBackupCodec.read(endless) }
        assertThrows(IllegalArgumentException::class.java) { FavoriteBackupCodec.encode(List(2001) { location() }) }
    }

    @Test fun mergeDeduplicatesAndChangesCollidingIdsWithoutOverwriting() {
        val storage = Storage(); val repository = FavoriteRepository(storage)
        val original = repository.save(location())
        val other = location(27.0).copy(id = original.id)
        assertEquals(FavoriteImportResult(1, 2), repository.importMerged(listOf(original.copy(name = "不会覆盖"), other, other)))
        val all = repository.all()
        assertEquals(original, all.single { it.id == original.id })
        assertEquals(2, all.map { it.id }.distinct().size)
        assertEquals(FavoriteImportResult(0, 1), repository.importMerged(listOf(other)))
    }

    @Test fun failedEntryOrIndexWriteRollsBackWholeBatch() {
        for (indexFailure in listOf(false, true)) {
            val storage = Storage(); val repository = FavoriteRepository(storage)
            repository.save(location())
            val before = storage.values.toMap()
            val additions = listOf(location(27.0), location(28.0))
            storage.failAt = if (indexFailure) "favorite_index_v1" else "entry_${additions.last().id}"
            assertThrows(IllegalStateException::class.java) { repository.importMerged(additions) }
            assertEquals(before, storage.values)
        }
    }
}
