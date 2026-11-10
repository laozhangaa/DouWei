package com.laozhang.mockgps.manager

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.laozhang.mockgps.model.FavoriteEntry
import com.laozhang.mockgps.model.RouteMetadata
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** A data-only allowlist: map keys, account settings and history never enter this format. */
object FavoriteBackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 2_000
    private val gson = Gson()
    private data class Backup(val format: String = "douwei-favorites", val version: Int = 1,
                              val exportedAt: Long = System.currentTimeMillis(), val favorites: List<FavoriteEntry>)

    fun encode(entries: List<FavoriteEntry>): String {
        validate(entries)
        val text = gson.toJson(Backup(favorites = entries.map { entry ->
            entry.copy(routeMetadata = entry.routeMetadata ?: RouteMetadata())
        }))
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "收藏数据超过 16 MB，请减少路线后导出" }
        return text
    }

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "备份文件超过 16 MB" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }

    fun decode(text: String): List<FavoriteEntry> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件超过 16 MB" }
        try {
            val root = JsonParser().parse(text).asJsonObject
            require(root.get("format")?.asString == "douwei-favorites") { "请选择豆位导出的收藏备份文件" }
            require(root.get("version")?.toString() == "1") { "不支持此备份版本，请更新豆位后重试" }
            val array = root.getAsJsonArray("favorites") ?: error("备份缺少收藏数据")
            require(array.size() <= MAX_ENTRIES) { "一次最多导入 2000 条收藏" }
            val entries = array.map {
                requireCoordinateFields(it)
                gson.fromJson(it, FavoriteEntry::class.java)
            }
            validate(entries)
            require(entries.all { (it.routeMetadata?.coordinateSystem ?: "gcj02") == "gcj02" }) {
                "此文件不是豆位使用的 GCJ-02 收藏坐标，请重新导出"
            }
            return entries
        } catch (error: Exception) {
            if (error.javaClass == IllegalArgumentException::class.java && error.message?.startsWith("com.google.gson") != true) throw error
            throw IllegalArgumentException("备份格式或收藏内容无效，请选择完整的豆位备份文件", error)
        }
    }

    /** Gson gives absent primitive numbers zero; that must not become a valid location. */
    private fun requireCoordinateFields(entry: JsonElement) {
        val root = entry.asJsonObject
        fun coordinates(array: JsonElement?) {
            require(array != null && array.isJsonArray) { "备份缺少地点或道路数据" }
            array.asJsonArray.forEach { element ->
                val point = element.asJsonObject
                for (name in listOf("latitude", "longitude")) {
                    val number = point.get(name)
                    require(number != null && number.isJsonPrimitive && number.asJsonPrimitive.isNumber && number.asDouble.isFinite()) { "备份坐标缺失或无效" }
                }
            }
        }
        coordinates(root.get("points"))
        if (root.get("kind")?.asString == "ROUTE") coordinates(root.get("path"))
    }

    private fun validate(entries: List<FavoriteEntry>) {
        require(entries.size <= MAX_ENTRIES) { "一次最多处理 2000 条收藏" }
        require(entries.sumOf { it.path.size.toLong() } <= 500_000) { "备份道路节点过多，请分批处理" }
        entries.forEach { it.validate() }
    }
}

data class FavoriteImportResult(val added: Int, val skipped: Int)
