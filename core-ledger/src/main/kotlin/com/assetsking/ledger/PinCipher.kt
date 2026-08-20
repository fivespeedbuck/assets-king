package com.assetsking.ledger

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份加密（REQ 备份§4）：6 位数字密码经 PBKDF2 派生密钥，再用 AES-GCM 加密并校验完整性。
 *
 * 6 位 PIN 仍不具备长密码的抗暴力强度，但错误 PIN 或文件被篡改时会明确失败，绝不能
 * 静默产出乱码再覆盖当前数据库。decrypt 保留旧 XOR 格式兼容，恢复层会再校验 SQLite。
 */
object PinCipher {
    private val magic = "AKB1".toByteArray(Charsets.US_ASCII)
    private const val saltSize = 16
    private const val ivSize = 12
    private const val tagBits = 128
    private const val iterations = 120_000
    private const val keySize = 32
    private val random = SecureRandom()

    private fun requireValidPin(pin: String) {
        require(pin.length == 6 && pin.all { it.isDigit() }) { "备份密码必须是 6 位数字" }
    }

    private fun key(pin: String, salt: ByteArray): SecretKeySpec {
        val password = pin.toByteArray(Charsets.UTF_8)
        return try {
            SecretKeySpec(pbkdf2HmacSha256(password, salt), "AES")
        } finally {
            password.fill(0)
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 的标准单块实现（RFC 8018）。
     *
     * 6 位 PIN 只需要 32 字节密钥，因此只派生第一个块。这里不使用 Android 的
     * SecretKeyFactory：部分 OriginOS 设备在同进程连续调用约五次后会永久休眠。
     * 算法、迭代次数和输出与旧实现完全一致，已有 AKB1 备份仍可恢复。
     */
    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))

        var previous = ByteArray(keySize)
        var current = ByteArray(keySize)
        val derived = ByteArray(keySize)
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        mac.doFinal(previous, 0)
        previous.copyInto(derived)

        repeat(iterations - 1) {
            mac.update(previous)
            mac.doFinal(current, 0)
            current.indices.forEach { index ->
                derived[index] = (derived[index].toInt() xor current[index].toInt()).toByte()
            }
            val swap = previous
            previous = current
            current = swap
        }
        previous.fill(0)
        current.fill(0)
        return derived
    }

    fun encrypt(data: ByteArray, pin: String): ByteArray {
        requireValidPin(pin)
        val salt = ByteArray(saltSize).also(random::nextBytes)
        val iv = ByteArray(ivSize).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pin, salt), GCMParameterSpec(tagBits, iv))
        val encrypted = cipher.doFinal(data)
        return ByteBuffer.allocate(magic.size + salt.size + iv.size + encrypted.size)
            .put(magic)
            .put(salt)
            .put(iv)
            .put(encrypted)
            .array()
    }

    fun decrypt(data: ByteArray, pin: String): ByteArray {
        requireValidPin(pin)
        if (!data.hasModernHeader()) return legacyTransform(data, pin)
        require(data.size >= magic.size + saltSize + ivSize + tagBits / 8) { "备份文件已损坏" }

        val buffer = ByteBuffer.wrap(data).apply { position(magic.size) }
        val salt = ByteArray(saltSize).also(buffer::get)
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(pin, salt), GCMParameterSpec(tagBits, iv))
        return cipher.doFinal(encrypted)
    }

    private fun ByteArray.hasModernHeader(): Boolean =
        size >= magic.size && magic.indices.all { this[it] == magic[it] }

    /** 仅用于读取 2026-08-20 前已经生成的备份。新备份一律使用 AES-GCM。 */
    private fun legacyTransform(data: ByteArray, pin: String): ByteArray {
        val key = legacyStream(pin, data.size)
        return ByteArray(data.size) { i -> (data[i].toInt() xor (key[i].toInt() and 0xFF)).toByte() }
    }

    private fun legacyStream(pin: String, length: Int): ByteArray {
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        val out = ByteArray(length)
        var counter = 0L
        var written = 0
        while (written < length) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(pinBytes)
            // big-endian counter 保证每块密钥不同
            md.update(ByteArray(8) { i -> ((counter shr (56 - i * 8)) and 0xFF).toByte() })
            val block = md.digest()
            val n = minOf(block.size, length - written)
            block.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }
}
