package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PinCipherTest {

    @Test
    fun `round trip restores original data`() {
        val data = "账户流水与负债数据的完整备份".toByteArray(Charsets.UTF_8)
        val encrypted = PinCipher.encrypt(data, "123456")
        assertFalse(encrypted.contentEquals(data))
        assertContentEquals(data, PinCipher.decrypt(encrypted, "123456"))
    }

    @Test
    fun `wrong pin produces garbage not original`() {
        val data = ByteArray(256) { it.toByte() }
        val encrypted = PinCipher.encrypt(data, "123456")
        assertFalse(PinCipher.decrypt(encrypted, "654321").contentEquals(data))
    }

    @Test
    fun `pin must be 6 digits`() {
        assertFailsWith<IllegalArgumentException> { PinCipher.encrypt(ByteArray(4), "12345") }
        assertFailsWith<IllegalArgumentException> { PinCipher.encrypt(ByteArray(4), "abcdef") }
    }

    @Test
    fun `same pin decrypts deterministically`() {
        val data = ByteArray(128) { (it * 7).toByte() }
        assertContentEquals(PinCipher.encrypt(data, "000000"), PinCipher.encrypt(data, "000000"))
    }
}
