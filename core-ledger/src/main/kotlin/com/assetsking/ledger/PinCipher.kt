package com.assetsking.ledger

import java.security.MessageDigest

/**
 * 备份加密（REQ 备份§4）：6 位数字密码 → SHA-256 → XOR 密钥流。
 *
 * ponytail: XOR 流加密强度明显低于长密码 AES；REQ 明确接受——产品提示「6 位密码主要
 * 防止文件被随手打开，强度低于长密码；密码遗忘后备份无法恢复」。密钥流用
 * SHA-256(PIN 字节) 反复扩展（counter 拼接），不依赖随机数（同 PIN 可重复解密）。
 */
object PinCipher {
    private fun stream(pin: String, length: Int): ByteArray {
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

    fun encrypt(data: ByteArray, pin: String): ByteArray {
        require(pin.length == 6 && pin.all { it.isDigit() }) { "备份密码必须是 6 位数字" }
        val key = stream(pin, data.size)
        return ByteArray(data.size) { i -> (data[i].toInt() xor (key[i].toInt() and 0xFF)).toByte() }
    }

    /** 与 encrypt 对称；密码错时静默产出乱码（无法校验），产品层已提示遗忘无法恢复。 */
    fun decrypt(data: ByteArray, pin: String): ByteArray = encrypt(data, pin)
}
