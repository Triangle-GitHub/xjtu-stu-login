// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.core

/**
 * 极简 JSON 解析（本项目原创）：只处理深澜响应那点形状 —— 顶层对象、标量、嵌套对象与数组，
 * 不追求完整 JSON 规范（注：工程上足够，且有 mock 网关全链路测试兜底）。
 */
object MiniJson {
    class ParseException(msg: String) : Exception(msg)

    fun parseObject(text: String): Map<String, Any?> {
        val t = text.trim()
        if (!t.startsWith("{")) throw ParseException("not an object: ${t.take(40)}")
        val map = linkedMapOf<String, Any?>()
        var i = 1
        val n = t.length
        while (i < n) {
            while (i < n && t[i].isWhitespace()) i++
            if (i >= n) break
            if (t[i] == '}') break
            if (t[i] == ',') { i++; continue }
            // key
            if (t[i] != '"') throw ParseException("expected key quote at $i")
            val k = readString(t, i)
            i = k.second
            while (i < n && t[i].isWhitespace()) i++
            if (t[i] != ':') throw ParseException("expected ':' at $i")
            i++
            while (i < n && t[i].isWhitespace()) i++
            val v = readValue(t, i)
            map[k.first] = v.first
            i = v.second
        }
        return map
    }

    private fun readString(t: String, start: Int): Pair<String, Int> {
        var i = start + 1
        val sb = StringBuilder()
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' -> {
                    i++
                    when (t[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> {
                            val hex = t.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(t[i])
                    }
                }
                c == '"' -> return sb.toString() to (i + 1)
                else -> sb.append(c)
            }
            i++
        }
        throw ParseException("unterminated string")
    }

    private fun readValue(t: String, start: Int): Pair<Any?, Int> {
        var i = start
        val c = t[i]
        return when {
            c == '"' -> readString(t, i)
            c == '{' -> {
                // 嵌套对象：递归解析为 Map
                var depth = 0
                var j = i
                while (j < t.length) {
                    if (t[j] == '{') depth++
                    else if (t[j] == '}') { depth--; if (depth == 0) break }
                    j++
                }
                if (j >= t.length) throw ParseException("unterminated object")
                return parseObject(t.substring(i, j + 1)) to (j + 1)
            }
            c == '[' -> {
                // 数组：递归解析元素为 List
                val list = mutableListOf<Any?>()
                var j = i + 1
                while (j < t.length) {
                    while (j < t.length && t[j].isWhitespace()) j++
                    if (j >= t.length) break
                    if (t[j] == ']') return list to (j + 1)
                    if (t[j] == ',') { j++; continue }
                    val v = readValue(t, j)
                    list.add(v.first)
                    j = v.second
                }
                throw ParseException("unterminated array")
            }
            c == 't' && t.startsWith("true", i) -> true to (i + 4)
            c == 'f' && t.startsWith("false", i) -> false to (i + 5)
            c == 'n' && t.startsWith("null", i) -> null to (i + 4)
            c == '-' || c.isDigit() -> {
                var j = i
                while (j < t.length && (t[j].isDigit() || t[j] == '.' || t[j] == '-' || t[j] == '+' || t[j] == 'e' || t[j] == 'E')) j++
                val num = t.substring(i, j)
                if (num.contains('.')) num.toDouble() to j else num.toLong() to j
            }
            else -> throw ParseException("unexpected char '$c' at $i")
        }
    }
}

/** Go url.QueryEscape 兼容编码（保留 [0-9A-Za-z-_.~]，空格 -> +，其余 %XX）—— 与 Go 的 url.Values.Encode 行为一致 */
fun goQueryEscape(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        when {
            ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
            ch == ' ' -> sb.append('+')
            else -> sb.append('%').append("%02X".format(c))
        }
    }
    return sb.toString()
}

/**
 * 连接工厂：把「怎么建 socket」交给调用方。
 *
 * Android 侧的用途：把请求绑定到指定 Network（`Network.openConnection(url)`）——
 * VPN（含校园里常见的覆盖网）会抢走默认路由，绑到 WiFi 网卡后到校园网关的报文依旧直出、不穿隧道，
 * 且源 IP 与网关看到的 WiFi IP 一致。引擎保持零 Android 依赖，这里只用 JDK 类型。
 */
fun interface ConnectionFactory {
    /** 建立到 url 的连接；失败请抛 IOException */
    @Throws(java.io.IOException::class)
    fun open(url: java.net.URL): java.net.HttpURLConnection
}

/**
 * 深澜 Srun 客户端（纯 JDK，JVM / Android 通用）。
 *
 * 端点、参数与算法见 docs/PROTOCOL.md；本实现按规范编写。
 * 请求形如 `GET {path}?callback=jQuery{rand}_{ms}&{k=v}&_={ms}`，响应是 `callback({...})`。
 */
class SrunClient(
    val baseUrl: String = "http://10.6.18.2/",
    val timeoutMs: Int = 5000,
    val userAgent: String = DEFAULT_UA,
    /** 可选：接管连接创建（Android 传入绑定到 WiFi 的工厂，以绕开 VPN 抢占的默认路由） */
    private val connectionFactory: ConnectionFactory? = null,
) {

    companion object {
        /** 与主流客户端保持一致的 UA（部分网关会看） */
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    /** 取挑战串（token） */
    fun getChallenge(username: String, ip: String): String {
        val res = request("cgi-bin/get_challenge", mapOf("username" to username, "ip" to ip))
        return res["challenge"] as? String
            ?: throw java.io.IOException("no challenge in response: $res")
    }

    /** 在线状态：`rad_user_info` 的 error == "ok" 且能拿到 client_ip/online_ip */
    fun loginStatus(): Pair<Boolean, String?> {
        val res = request("cgi-bin/rad_user_info", emptyMap())
        val online = res["error"] as? String == "ok"
        val ip = (res["client_ip"] as? String) ?: (res["online_ip"] as? String)
        return online to ip
    }

    /**
     * 完整登录。
     *
     * [clientIP] 必须是**网关认的地址**（`rad_user_info` 的 `client_ip`，见 PROTOCOL §5），
     * 不是本机网卡地址 —— 两者在部分区域会不一致，用错即 `login_error`。
     */
    fun doLogin(form: LoginForm, meta: LoginMeta, clientIP: String): SrunResult {
        val username = withSuffix(form)
        val token = getChallenge(username, clientIP)
        val md5Hex = SrunCrypto.hmacMd5Hex(token, form.password)
        val info = SrunCrypto.encryptedInfo(form, meta, clientIP, token)
        val chksum = SrunCrypto.chksum(
            token, username, md5Hex, meta.acid, clientIP, meta.n, meta.type, info
        )

        val res = request(
            "cgi-bin/srun_portal",
            linkedMapOf(
                "action" to "login",
                "username" to username,
                "password" to "{MD5}$md5Hex",
                "ac_id" to meta.acid,
                "ip" to clientIP,
                "info" to info,
                "chksum" to chksum,
                "n" to meta.n,
                "type" to meta.type,
                "os" to meta.os,
                "name" to meta.name,
                "double_stack" to if (meta.doubleStack) "1" else "0",
            ),
        )
        return interpret(res) { ip -> SrunResult.Ok(ip ?: clientIP) }
    }

    /** 登出（action=logout + 账号/域/ip） */
    fun doLogout(form: LoginForm, meta: LoginMeta, clientIP: String): SrunResult {
        val res = request(
            "cgi-bin/srun_portal",
            linkedMapOf(
                "action" to "logout",
                "username" to withSuffix(form),
                "ac_id" to meta.acid,
                "ip" to clientIP,
            ),
        )
        return interpret(res) { SrunResult.Ok(null) }
    }

    /** 带后缀的账号：`学号@stu`（后缀为空则原样） */
    private fun withSuffix(form: LoginForm): String =
        if (form.userType.isEmpty()) form.username else "${form.username}@${form.userType}"

    /** 统一解释 srun 响应：error == ok 即成功，否则透传网关错误串 */
    private fun interpret(res: Map<String, Any?>, onOk: (String?) -> SrunResult): SrunResult {
        val error = res["error"] as? String
            ?: throw java.io.IOException("no error field in response: $res")
        if (error != "ok") return SrunResult.Rejected(error, res.toString())
        val ip = (res["client_ip"] as? String) ?: (res["online_ip"] as? String)
        return onOk(ip)
    }

    /**
     * 发一次 JSONP GET。
     *
     * @throws java.io.IOException HTTP 非 200 或响应不是 JSON 对象
     */
    fun request(path: String, query: Map<String, String>): Map<String, Any?> {
        val url = buildUrl(path, query)
        val conn = openConnection(url)
        val body = try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code != 200) throw java.io.IOException("HTTP $code for $path: ${text.take(120)}")
            text
        } finally {
            runCatching { conn.disconnect() }
        }
        return parseCallbackBody(body)
    }

    /** 拼 URL：callback + 业务参数 + 时间戳（网关要求 callback/_ 存在） */
    private fun buildUrl(path: String, query: Map<String, String>): String {
        val ms = System.currentTimeMillis()
        val nonce = (1..21).joinToString("") { ((1..9).random()).toString() }
        val sb = StringBuilder(base())
        sb.append(path).append("?callback=jQuery").append(nonce).append('_').append(ms)
        for ((key, value) in query) {
            if (value.isEmpty()) continue
            sb.append('&').append(key).append('=').append(goQueryEscape(value))
        }
        sb.append("&_=").append(ms)
        return sb.toString()
    }

    private fun base(): String = if (baseUrl.endsWith("/")) baseUrl else baseUrl + "/"

    private fun openConnection(url: String): java.net.HttpURLConnection {
        val parsed = java.net.URL(url)
        return connectionFactory?.open(parsed)
            ?: (parsed.openConnection() as java.net.HttpURLConnection)
    }

    /** 剥掉 `callback(...)` 外壳（容错：不是 JSONP 就按纯 JSON 解析） */
    private fun parseCallbackBody(body: String): Map<String, Any?> {
        var text = body.trim()
        if (text.startsWith("jQuery") || text.startsWith("jsonp")) {
            val open = text.indexOf('(')
            val close = text.lastIndexOf(')')
            if (open >= 0 && close > open) text = text.substring(open + 1, close).trim()
        }
        return MiniJson.parseObject(text)
    }
}
