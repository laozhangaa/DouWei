package com.laozhang.mockgps.manager

import com.google.gson.Gson
import com.laozhang.mockgps.model.NavigationRecovery
import com.laozhang.mockgps.model.PlaybackPosition
import com.laozhang.mockgps.model.RecoveryRoute

interface RecoveryStorage {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun remove(name: String)
}

/** Large immutable geometry is written once. Frequent checkpoints only rewrite the small cursor. */
class NavigationRecoveryRepository(private val storage: RecoveryStorage) {
    private val gson = Gson()
    private data class Cursor(val routeId: String, val position: PlaybackPosition, val savedAt: Long)

    fun start(route: RecoveryRoute, position: PlaybackPosition = PlaybackPosition()) {
        route.validate(); position.validate()
        storage.write("route", gson.toJson(route))
        progress(route.id, position)
    }

    fun progress(routeId: String, position: PlaybackPosition) {
        position.validate()
        storage.write("progress", gson.toJson(Cursor(routeId, position, System.currentTimeMillis())))
    }

    fun load(): NavigationRecovery? {
        val text = storage.read("route") ?: return null
        require(text.toByteArray(Charsets.UTF_8).size <= FavoriteBackupCodec.MAX_BYTES) { "恢复文件过大，请重新规划" }
        val route = gson.fromJson(text, RecoveryRoute::class.java).also { it.validate() }
        val cursor = storage.read("progress")?.let { value ->
            require(value.length <= 4096) { "恢复进度文件无效" }
            gson.fromJson(value, Cursor::class.java)
        }?.takeIf { it.routeId == route.id }
        cursor?.position?.validate()
        return NavigationRecovery(route, cursor?.position ?: PlaybackPosition(), cursor?.savedAt ?: route.createdAt)
    }

    fun clear() { storage.remove("route"); storage.remove("progress") }
}
