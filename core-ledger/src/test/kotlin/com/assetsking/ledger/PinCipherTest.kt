package com.assetsking.ledger

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PinCipherTest {

    @Test
    fun `round trip restores original data`() {
        val data = "账户流水与负债数据的完整备份".toByteArray(Charsets.UTF_8)
        val encrypted = PinCipher.encrypt(data, "123456")
        assertFalse(encrypted.contentEquals(data))
        assertContentEquals(data, PinCipher.decrypt(encrypted, "123456"))
    }

    @Test
    fun `wrong pin is rejected`() {
        val data = ByteArray(256) { it.toByte() }
        val encrypted = PinCipher.encrypt(data, "123456")
        assertFailsWith<GeneralSecurityException> { PinCipher.decrypt(encrypted, "654321") }
    }

    @Test
    fun `pin must be 6 digits`() {
        assertFailsWith<IllegalArgumentException> { PinCipher.encrypt(ByteArray(4), "12345") }
        assertFailsWith<IllegalArgumentException> { PinCipher.encrypt(ByteArray(4), "abcdef") }
    }

    @Test
    fun `same pin uses a fresh nonce for every backup`() {
        val data = ByteArray(128) { (it * 7).toByte() }
        val first = PinCipher.encrypt(data, "000000")
        val second = PinCipher.encrypt(data, "000000")
        assertFalse(first.contentEquals(second))
        assertContentEquals(data, PinCipher.decrypt(first, "000000"))
        assertContentEquals(data, PinCipher.decrypt(second, "000000"))
    }

    @Test
    fun `manual KDF remains compatible with standard PBKDF2 backups`() {
        val data = "旧版标准 PBKDF2 备份".toByteArray()
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 16).toByte() }
        val spec = PBEKeySpec("123456".toCharArray(), salt, 120_000, 256)
        val standardKey = try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES"
            )
        } finally {
            spec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, standardKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(data)
        val backup = ByteBuffer.allocate(4 + salt.size + iv.size + encrypted.size)
            .put("AKB1".toByteArray(Charsets.US_ASCII))
            .put(salt)
            .put(iv)
            .put(encrypted)
            .array()

        assertContentEquals(data, PinCipher.decrypt(backup, "123456"))
    }

    @Test
    fun `tampered backup is rejected`() {
        val encrypted = PinCipher.encrypt("账本".toByteArray(), "123456")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> { PinCipher.decrypt(encrypted, "123456") }
    }

    @Test
    fun `legacy xor backup remains readable`() {
        val data = "旧版资产大王备份".toByteArray()
        val encrypted = legacyTransform(data, "123456")
        assertContentEquals(data, PinCipher.decrypt(encrypted, "123456"))
    }

    private fun legacyTransform(data: ByteArray, pin: String): ByteArray {
        val out = ByteArray(data.size)
        var counter = 0L
        var written = 0
        while (written < data.size) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(pin.toByteArray(Charsets.UTF_8))
            md.update(ByteArray(8) { i -> ((counter shr (56 - i * 8)) and 0xFF).toByte() })
            val block = md.digest()
            repeat(minOf(block.size, data.size - written)) { offset ->
                val index = written + offset
                out[index] = (data[index].toInt() xor (block[offset].toInt() and 0xFF)).toByte()
            }
            written += minOf(block.size, data.size - written)
            counter++
        }
        return out
    }
}
