package com.laozhang.mockgps.manager

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.laozhang.mockgps.model.FavoriteEntry
import com.laozhang.mockgps.model.FavoriteKind
import java.util.UUID

interface FavoriteStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** A separate index and one value per favorite avoid rewriting every stored road polyline. */
class FavoriteRepository(private val storage: FavoriteStorage) {
    private val gson = Gson()
    private val indexKey = "favorite_index_v1"
    private fun entryKey(id: String) = "entry_$id"
    private fun index(): List<String> = try {
        gson.fromJson<List<String>>(storage.get(indexKey), object : TypeToken<List<String>>() {}.type)
            ?.distinct().orEmpty()
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun all(kind: FavoriteKind? = null): List<FavoriteEntry> = index().mapNotNull { id ->
        try {
            gson.fromJson(storage.get(entryKey(id)), FavoriteEntry::class.java)?.also {
                it.validate()
                require(it.id == id)
            }
        } catch (_: Exception) { null }
    }.filter { kind == null || it.kind == kind }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(entry: FavoriteEntry): FavoriteEntry {
        val trimmed = entry.copy(name = entry.name.trim())
        trimmed.validate()
        val identity = trimmed.identity()
        val existing = all(trimmed.kind).firstOrNull {
            it.id == trimmed.id || it.identity() == identity
        }
        val saved = trimmed.copy(id = existing?.id ?: trimmed.id,
            createdAt = existing?.createdAt ?: trimmed.createdAt, updatedAt = System.currentTimeMillis())
        storage.put(entryKey(saved.id), gson.toJson(saved))
        val ids = index()
        if (saved.id !in ids) {
            try {
                storage.put(indexKey, gson.toJson(ids + saved.id))
            } catch (error: Exception) {
                storage.remove(entryKey(saved.id))
                throw error
            }
        }
        return saved
    }

    @Synchronized
    fun rename(id: String, name: String): FavoriteEntry {
        val original = all().firstOrNull { it.id == id } ?: error("该收藏已被删除")
        return save(original.copy(name = name))
    }

    @Synchronized
    fun delete(id: String) {
        storage.put(indexKey, gson.toJson(index().filterNot { it == id }))
        storage.remove(entryKey(id))
    }

    /** Validate the entire batch before writes. Existing favorites are never overwritten. */
    @Synchronized
    fun importMerged(entries: List<FavoriteEntry>): FavoriteImportResult {
        require(entries.size <= FavoriteBackupCodec.MAX_ENTRIES) { "一次最多导入 2000 条收藏" }
        entries.forEach { it.validate() }
        val existing = all()
        val identities = existing.map { it.identity() }.toMutableSet()
        val ids = index().toMutableList()
        val additions = arrayListOf<FavoriteEntry>()
        for (entry in entries) {
            if (!identities.add(entry.identity())) continue
            val id = if (entry.id in ids) UUID.randomUUID().toString() else entry.id
            additions.add(entry.copy(id = id))
            ids.add(id)
        }
        if (additions.isEmpty()) return FavoriteImportResult(0, entries.size)
        val previousIndex = storage.get(indexKey)
        val oldValues = additions.associate { it.id to storage.get(entryKey(it.id)) }
        try {
            additions.forEach { storage.put(entryKey(it.id), gson.toJson(it)) }
            storage.put(indexKey, gson.toJson(ids))
        } catch (error: Exception) {
            runCatching {
                if (previousIndex == null) storage.remove(indexKey) else storage.put(indexKey, previousIndex)
            }.exceptionOrNull()?.let { error.addSuppressed(it) }
            oldValues.forEach { (id, original) ->
                runCatching {
                    if (original == null) storage.remove(entryKey(id)) else storage.put(entryKey(id), original)
                }.exceptionOrNull()?.let { error.addSuppressed(it) }
            }
            throw error
        }
        return FavoriteImportResult(additions.size, entries.size - additions.size)
    }
}
