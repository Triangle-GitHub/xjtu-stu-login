// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.core

/**
 * 深澜 Srun 的 XEncode（XXTEA 变体）。
 *
 * 按公开协议规范（docs/PROTOCOL.md）与 MIT 许可的参考实现
 * （BIT-srun-login-script，© 2020 coffeehat，全文见 licenses/MIT-BIT-srun-login-script.txt）
 * 独立编写；不包含任何 AGPL 代码。
 *
 * 规范要点：
 *  1. 明文与密钥都按「4 字节小端」打包成 32 位字，明文末组不足 4 字节补 0；
 *  2. 明文末尾追加一个「明文字节长度」元素，密钥不追加；
 *  3. 密钥不足 4 组补 0；参与运算的下标恒在 0..3，故本实现直接取前 4 组（等价且更直白）；
 *  4. 轮数 = 6 + 52/(n+1)，每轮累加 DELTA = 0x9E3779B9；
 *  5. 32 位语义：Kotlin Int 的 + 自然回绕，移位统一用 ushr/shl，无需到处 and 0xFFFFFFFF。
 *
 * 输出为原始字节（每字 4 字节小端），再由 [SrunB64] 过变体 base64。
 */
object XEncode {

    /** TEA 黄金分割增量 */
    private const val DELTA = 0x9E3779B9.toInt()

    /** 加密：[content] 明文，[key] 挑战串（token） */
    fun xEncode(content: String, key: String): ByteArray {
        if (content.isEmpty()) return ByteArray(0)
        val words = packWords(content.toByteArray(Charsets.UTF_8), withLength = true)
        val keyWords = packWords(key.toByteArray(Charsets.UTF_8), withLength = false).copyOf(4)
        return unpackWords(twist(words, keyWords))
    }

    /** 小端打包为 32 位字；[withLength] 为真时在末尾追加原始字节长度 */
    private fun packWords(src: ByteArray, withLength: Boolean): IntArray {
        val groups = (src.size + 3) / 4
        val out = IntArray(if (withLength) groups + 1 else groups)
        for (g in 0 until groups) {
            val base = g * 4
            var word = 0
            for (shift in 3 downTo 0) {
                val idx = base + shift
                val byte = if (idx < src.size) src[idx].toInt() and 0xFF else 0
                word = (word shl 8) or byte
            }
            out[g] = word
        }
        if (withLength) out[groups] = src.size
        return out
    }

    private fun unpackWords(words: IntArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) {
            val word = words[i]
            out[i * 4] = word.toByte()
            out[i * 4 + 1] = (word ushr 8).toByte()
            out[i * 4 + 2] = (word ushr 16).toByte()
            out[i * 4 + 3] = (word ushr 24).toByte()
        }
        return out
    }

    /** XXTEA 主循环（就地修改 [v]；[k] 恒为 4 个字） */
    private fun twist(v: IntArray, k: IntArray): IntArray {
        val last = v.size - 1
        var z = v[last]
        var y = v[0]
        var sum = 0
        var rounds = 6 + 52 / (last + 1)
        while (rounds-- > 0) {
            sum += DELTA
            val e = (sum ushr 2) and 3
            for (p in 0 until last) {
                y = v[p + 1]
                z = v[p] + mix(z, y, sum, k[(p and 3) xor e])
                v[p] = z
            }
            y = v[0]
            z = v[last] + mix(z, y, sum, k[(last and 3) xor e])
            v[last] = z
        }
        return v
    }

    /** 单步混淆函数 MX */
    private fun mix(z: Int, y: Int, sum: Int, k: Int): Int =
        ((z ushr 5) xor (y shl 2)) +
            (((y ushr 3) xor (z shl 4)) xor (sum xor y)) +
            (k xor z)
}

/**
 * 深澜变体 base64：字母表为协议常量（与标准表不同），分组与 '=' 补位同标准实现。
 */
object SrunB64 {

    private const val ALPHABET = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"

    private const val PAD = '='

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            emitTriple(sb, bytes, i)
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val word = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[word ushr 18])
                    .append(ALPHABET[(word ushr 12) and 0x3F])
                    .append(PAD).append(PAD)
            }
            2 -> {
                val word = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[word ushr 18])
                    .append(ALPHABET[(word ushr 12) and 0x3F])
                    .append(ALPHABET[(word ushr 6) and 0x3F])
                    .append(PAD)
            }
        }
        return sb.toString()
    }

    private fun emitTriple(sb: StringBuilder, bytes: ByteArray, i: Int) {
        val word = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(ALPHABET[word ushr 18])
            .append(ALPHABET[(word ushr 12) and 0x3F])
            .append(ALPHABET[(word ushr 6) and 0x3F])
            .append(ALPHABET[word and 0x3F])
    }
}
