package com.assetsking.ledger

import java.nio.ByteBuffer

/** 单文件备份明文容器；整体再交给 [PinCipher] 加密。 */
object BackupBundle {
    private val magic = "AKPK1".toByteArray(Charsets.US_ASCII)
    private const val headerSize = 5 + Int.SIZE_BYTES * 2

    data class Contents(
        val database: ByteArray,
        val preferencesJson: ByteArray
    )

    fun pack(database: ByteArray, preferencesJson: ByteArray): ByteArray {
        require(database.isNotEmpty()) { "数据库备份不能为空" }
        val size = Math.addExact(headerSize, Math.addExact(database.size, preferencesJson.size))
        return ByteBuffer.allocate(size)
            .put(magic)
            .putInt(database.size)
            .putInt(preferencesJson.size)
            .put(database)
            .put(preferencesJson)
            .array()
    }

    /** 返回 null 表示旧版直接加密数据库文件；识别到新格式但损坏时抛错。 */
    fun unpack(data: ByteArray): Contents? {
        if (!data.hasMagic()) return null
        require(data.size >= headerSize) { "单文件备份头已损坏" }
        val buffer = ByteBuffer.wrap(data).apply { position(magic.size) }
        val databaseSize = buffer.int
        val preferencesSize = buffer.int
        require(databaseSize > 0 && preferencesSize >= 0) { "单文件备份长度无效" }
        require(databaseSize.toLong() + preferencesSize.toLong() == buffer.remaining().toLong()) {
            "单文件备份内容不完整"
        }
        val database = ByteArray(databaseSize).also(buffer::get)
        val preferences = ByteArray(preferencesSize).also(buffer::get)
        return Contents(database, preferences)
    }

    private fun ByteArray.hasMagic(): Boolean =
        size >= magic.size && magic.indices.all { this[it] == magic[it] }
}
