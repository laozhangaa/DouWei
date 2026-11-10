package com.laozhang.mockgps.manager

import android.os.Handler
import android.os.Looper
import com.laozhang.mockgps.model.FavoriteEntry
import com.laozhang.mockgps.model.FavoriteKind
import com.tencent.mmkv.MMKV
import java.util.concurrent.Executors
import java.io.InputStream
import java.io.OutputStream

object FavoritesManager {
    private val worker = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val repository by lazy {
        val mmkv = requireNotNull(MMKV.mmkvWithID("douwei_favorites"))
        FavoriteRepository(object : FavoriteStorage {
            override fun get(key: String) = mmkv.decodeString(key)
            override fun put(key: String, value: String) {
                check(mmkv.encode(key, value)) { "收藏保存失败，请检查手机存储空间" }
                mmkv.sync()
            }
            override fun remove(key: String) { mmkv.removeValueForKey(key); mmkv.sync() }
        })
    }

    private fun <T> submit(callback: (Result<T>) -> Unit, task: () -> T) {
        worker.execute {
            val result = runCatching(task)
            main.post { callback(result) }
        }
    }

    fun list(kind: FavoriteKind, callback: (Result<List<FavoriteEntry>>) -> Unit) =
        submit(callback) { repository.all(kind) }
    fun save(entry: FavoriteEntry, callback: (Result<FavoriteEntry>) -> Unit) =
        submit(callback) { repository.save(entry) }
    fun rename(id: String, name: String, callback: (Result<FavoriteEntry>) -> Unit) =
        submit(callback) { repository.rename(id, name) }
    fun delete(id: String, callback: (Result<Unit>) -> Unit) =
        submit(callback) { repository.delete(id) }
    fun counts(callback: (Result<Pair<Int, Int>>) -> Unit) = submit(callback) {
        val entries = repository.all()
        entries.count { it.kind == FavoriteKind.LOCATION } to entries.count { it.kind == FavoriteKind.ROUTE }
    }
    fun exportBackup(open: () -> OutputStream?, callback: (Result<Int>) -> Unit) = submit(callback) {
        val entries = repository.all()
        val text = FavoriteBackupCodec.encode(entries)
        requireNotNull(open()) { "无法创建备份文件，请重新选择保存位置" }.use {
            it.write(text.toByteArray(Charsets.UTF_8)); it.flush()
        }
        entries.size
    }
    fun readBackup(open: () -> InputStream?, callback: (Result<List<FavoriteEntry>>) -> Unit) = submit(callback) {
        requireNotNull(open()) { "无法读取备份文件，请重新选择" }.use { FavoriteBackupCodec.decode(FavoriteBackupCodec.read(it)) }
    }
    fun importBackup(entries: List<FavoriteEntry>, callback: (Result<FavoriteImportResult>) -> Unit) =
        submit(callback) { repository.importMerged(entries) }
}
