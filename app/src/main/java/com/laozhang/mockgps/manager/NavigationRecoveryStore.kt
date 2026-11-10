package com.laozhang.mockgps.manager

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.blankj.utilcode.util.Utils
import com.laozhang.mockgps.model.NavigationRecovery
import com.laozhang.mockgps.model.PlaybackPosition
import com.laozhang.mockgps.model.RecoveryRoute
import java.io.File
import java.util.concurrent.Executors

object NavigationRecoveryStore {
    private val io = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile var cached: NavigationRecovery? = null
        private set
    @Volatile var lastError: String? = null
        private set
    private var lastSavedAt = 0L
    private var revision = 0L
    private val repository by lazy {
        val dir = File(Utils.getApp().filesDir, "navigation_resume").apply { mkdirs() }
        NavigationRecoveryRepository(object : RecoveryStorage {
            private fun file(name: String) = AtomicFile(File(dir, "$name.json"))
            override fun read(name: String): String? {
                val file = file(name)
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
                require(file.baseFile.length() <= FavoriteBackupCodec.MAX_BYTES) { "导航恢复文件过大" }
                return file.openRead().use { FavoriteBackupCodec.read(it) }
            }
            override fun write(name: String, value: String) {
                val file = file(name)
                val stream = file.startWrite()
                try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                catch (error: Exception) { file.failWrite(stream); throw error }
            }
            override fun remove(name: String) { file(name).delete() }
        })
    }

    private fun write(clearError: Boolean = false, task: () -> Unit) {
        io.execute {
            try { task(); if (clearError) lastError = null }
            catch (_: Exception) { lastError = "导航进度保存失败，请检查手机存储空间" }
        }
    }

    /** Called from the main thread; disk I/O never shares the location injection worker. */
    fun start(route: RecoveryRoute, position: PlaybackPosition = PlaybackPosition()) {
        revision++
        cached = NavigationRecovery(route, position)
        lastSavedAt = android.os.SystemClock.elapsedRealtime()
        write(true) { repository.start(route, position) }
    }

    fun update(routeId: String, position: PlaybackPosition, force: Boolean = false) {
        val current = cached?.takeIf { it.route.id == routeId } ?: return
        revision++
        cached = current.copy(position = position, savedAt = System.currentTimeMillis())
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastSavedAt >= 5_000L) {
            lastSavedAt = now
            write { repository.progress(routeId, position) }
        }
    }

    fun load(callback: (Result<NavigationRecovery?>) -> Unit) {
        val requestedRevision = revision
        io.execute {
            val result = runCatching { repository.load() }
            main.post {
                if (revision == requestedRevision) {
                    if (result.isSuccess) cached = result.getOrNull()
                    callback(result)
                } else callback(Result.success(cached))
            }
        }
    }

    fun clear() {
        revision++
        cached = null
        write(true) { repository.clear() }
    }
}
