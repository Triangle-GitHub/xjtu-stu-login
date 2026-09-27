package org.xjtulogin.core

/**
 * 深澜 Srun XEncode（XTEA 变体）。
 * 移植自 BitSrunLoginGo pkg/srun/XEncode.go（AGPL-3.0，仅作协议蓝本逐行对应）；
 * 所有整数以 Long 携带低 32 位（每步 and 0xFFFFFFFF），等效 Go uint32 回绕。
 * 修正：密钥组补零按意图补满 4 组（上游 Go 的 for 循环条件每次迭代重算，
 * 会把 pwdk 停在 3 组 —— off-by-one 潜伏 bug；XJTU 网关 token>=16 字节不触发，
 * 见 docs/DESIGN.md §协议细节）。
 */
object XEncode {

    fun ordat(msg: ByteArray, idx: Int): Long =
        if (msg.size > idx) (msg[idx].toLong() and 0xFF) else 0L

    fun sensCode(content: ByteArray, keyFlag: Boolean, length: Int): LongArray {
        val pwd = mutableListOf<Long>()
        var i = 0
        while (i < length) {
            pwd.add(
                ordat(content, i) or
                    (ordat(content, i + 1) shl 8) or
                    (ordat(content, i + 2) shl 16) or
                    (ordat(content, i + 3) shl 24)
            )
            i += 4
        }
        if (keyFlag) pwd.add(length.toLong())
        return pwd.toLongArray()
    }

    fun lenCode(msg: LongArray, keyFlag: Boolean): ByteArray {
        val l = msg.size.toLong()
        var ll = (l - 1) shl 2
        if (keyFlag) {
            val m = msg[msg.size - 1]
            if (m < ll - 3 || m > ll) return ByteArray(0)
            ll = m
        }
        val out = java.io.ByteArrayOutputStream(msg.size * 4)
        for (v in msg) {
            out.write((v and 0xFF).toInt())
            out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt())
            out.write(((v shr 24) and 0xFF).toInt())
        }
        val t = out.toByteArray()
        return if (keyFlag) t.copyOfRange(0, ll.toInt()) else t
    }

    /** 加密：content -> 原始字节（与 Go 版 XEncode 一致） */
    fun xEncode(content: String, key: String): ByteArray {
        if (content.isEmpty()) return ByteArray(0)
        val cBytes = content.toByteArray(Charsets.UTF_8)
        return xEncodeBytes(cBytes, key.toByteArray(Charsets.UTF_8))
    }

    fun xEncodeBytes(cBytes: ByteArray, kBytes: ByteArray): ByteArray {
        val pwd = sensCode(cBytes, true, cBytes.size).toMutableList()

        var pwdk = sensCode(kBytes, false, kBytes.size).toMutableList()
        while (pwdk.size < 4) pwdk.add(0L) // 按意图补满 4 组

        val n = (pwd.size - 1).toLong()
        var z = pwd[n.toInt()]
        var y = pwd[0]
        val c = 0x86014019L or 0x183639A0L // 0x9E3779B9（TEA delta）
        var m: Long
        var e: Long
        var p: Long
        var q = Math.floor(6 + 52.0 / (n + 1)).toLong()
        var d = 0L

        while (0 < q) {
            d = (d + c) and 0xFFFFFFFFL
            e = (d shr 2) and 3
            p = 0
            while (p < n) {
                y = pwd[(p + 1).toInt()]
                m = ((z ushr 5) xor ((y shl 2) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
                m = (m + ((y ushr 3) xor ((z shl 4) and 0xFFFFFFFFL) xor (d xor y))) and 0xFFFFFFFFL
                m = (m + (pwdk[((p and 3) xor e).toInt()] xor z)) and 0xFFFFFFFFL
                pwd[p.toInt()] = (pwd[p.toInt()] + m) and 0xFFFFFFFFL
                z = pwd[p.toInt()]
                p += 1
            }
            y = pwd[0]
            m = ((z ushr 5) xor ((y shl 2) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            m = (m + ((y ushr 3) xor ((z shl 4) and 0xFFFFFFFFL) xor (d xor y))) and 0xFFFFFFFFL
            m = (m + (pwdk[((p and 3) xor e).toInt()] xor z)) and 0xFFFFFFFFL
            pwd[n.toInt()] = (pwd[n.toInt()] + m) and 0xFFFFFFFFL
            z = pwd[n.toInt()]
            q -= 1
        }
        return lenCode(pwd.toLongArray(), false)
    }

    /** 解密：与 BitSrunLoginGo 原版逐行对齐（含其不可逆缺陷 —— 上游 XDecode 解不出自己的
     *  XEncode 输出；本实现保持同样行为仅为 API 对齐，协议路径不依赖解密） */
    fun xDecode(encoded: ByteArray, key: String): String {
        if (encoded.isEmpty()) return ""
        val pwd = sensCode(encoded, false, encoded.size).toMutableList()

        var pwdk = sensCode(key.toByteArray(Charsets.UTF_8), false, key.toByteArray(Charsets.UTF_8).size).toMutableList()
        while (pwdk.size < 4) pwdk.add(0L)

        val n = (pwd.size - 1).toLong()
        var z = pwd[n.toInt()]
        var y = pwd[0]
        val c = 0x86014019L or 0x183639A0L
        var m: Long
        var e: Long
        var p: Long
        var q = Math.floor(6 + 52.0 / (n + 1)).toLong()
        var d = 0L

        while (q > 0) {
            d = (d + c) and 0xFFFFFFFFL
            e = (d shr 2) and 3
            p = n
            while (p > 0) {
                z = pwd[(p - 1).toInt()]
                m = ((z ushr 5) xor ((y shl 2) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
                m = (m + ((y ushr 3) xor ((z shl 4) and 0xFFFFFFFFL) xor (d xor y))) and 0xFFFFFFFFL
                m = (m + (pwdk[((p and 3) xor e).toInt()] xor z)) and 0xFFFFFFFFL
                pwd[p.toInt()] = (pwd[p.toInt()] - m) and 0xFFFFFFFFL
                y = pwd[p.toInt()]
                p -= 1
            }
            z = pwd[n.toInt()]
            m = ((z ushr 5) xor ((y shl 2) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            m = (m + ((y ushr 3) xor ((z shl 4) and 0xFFFFFFFFL) xor (d xor y))) and 0xFFFFFFFFL
            m = (m + (pwdk[((p and 3) xor e).toInt()] xor z)) and 0xFFFFFFFFL
            pwd[0] = (pwd[0] - m) and 0xFFFFFFFFL
            y = pwd[0]
            q -= 1
        }
        return String(lenCode(pwd.toLongArray(), true), Charsets.UTF_8)
    }
}

/** 深澜变体 base64（自定义字母表，输入 = XEncode 原始字节） */
object SrunB64 {
    private const val ALPHA = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
    private const val PADCHAR = '='

    fun encode(s: ByteArray): String {
        val imax = s.size - s.size % 3
        if (s.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < imax) {
            val b10 = ((s[i].toInt() and 0xFF) shl 16) or
                ((s[i + 1].toInt() and 0xFF) shl 8) or
                (s[i + 2].toInt() and 0xFF)
            sb.append(ALPHA[(b10 shr 18)]).append(ALPHA[(b10 shr 12) and 63])
                .append(ALPHA[(b10 shr 6) and 63]).append(ALPHA[b10 and 63])
            i += 3
        }
        if (s.size - imax == 1) {
            val b10 = (s[i].toInt() and 0xFF) shl 16
            sb.append(ALPHA[(b10 shr 18)]).append(ALPHA[(b10 shr 12) and 63]).append(PADCHAR).append(PADCHAR)
        } else if (s.size - imax == 2) {
            val b10 = ((s[i].toInt() and 0xFF) shl 16) or ((s[i + 1].toInt() and 0xFF) shl 8)
            sb.append(ALPHA[(b10 shr 18)]).append(ALPHA[(b10 shr 12) and 63]).append(ALPHA[(b10 shr 6) and 63]).append(PADCHAR)
        }
        return sb.toString()
    }
}