package org.xjtulogin.core

/** 极简 JSON 对象解析：仅顶层 { "key": scalar }（深澜响应形状），scalar = String/Long/Double/Boolean/null */
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

/** 深澜 Srun 客户端（纯 JDK，JVM / Android 通用） */
class SrunClient(
    val baseUrl: String = "http://10.6.18.2/",
    val timeoutMs: Int = 5000,
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
) {
    private fun base(): String = if (baseUrl.endsWith("/")) baseUrl else baseUrl + "/"

    /** GET {path}?{query}&callback=jQuery{21位}[1-9]_{ms}&_={ms}，返回 {} 剥壳后的 JSON 对象 */
    fun request(path: String, query: Map<String, String>): Map<String, Any?> {
        val rand = (1..21).joinToString("") { ((1..9).random()).toString() }
        val ms = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("callback=jQuery").append(rand).append('_').append(ms)
        for ((k, v) in query) {
            if (v.isEmpty()) continue
            sb.append('&').append(k).append('=').append(goQueryEscape(v))
        }
        sb.append("&_=").append(ms)
        val url = base() + path + "?" + sb
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "*/*")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code != 200) throw java.io.IOException("HTTP $code for $path: ${body.take(120)}")
        return parseCallbackBody(body, "jQuery")
    }

    /** 剥掉 callback(...) 外壳（容错：找不到括号就按纯 JSON 解析） */
    private fun parseCallbackBody(body: String, prefix: String): Map<String, Any?> {
        var t = body.trim()
        if (t.startsWith(prefix) || t.startsWith("jQuery")) {
            val open = t.indexOf('(')
            val close = t.lastIndexOf(')')
            if (open >= 0 && close > open) t = t.substring(open + 1, close).trim()
        }
        return MiniJson.parseObject(t)
    }

    /** GET get_challenge -> token */
    fun getChallenge(username: String, ip: String): String {
        val res = request("cgi-bin/get_challenge", mapOf("username" to username, "ip" to ip))
        val token = res["challenge"] as? String ?: throw java.io.IOException("no challenge in response: $res")
        return token
    }

    /** 在线状态：rad_user_info 的 error == "ok" 且拿到 client_ip/online_ip */
    fun loginStatus(): Pair<Boolean, String?> {
        val res = request("cgi-bin/rad_user_info", emptyMap())
        val isOnline = res["error"] as? String == "ok"
        val ip = (res["client_ip"] as? String) ?: (res["online_ip"] as? String)
        return isOnline to ip
    }

    /** 完整登录（对应 BitSrunLoginGo DoLogin）；clientIP 由调用方传入（Android 从 WifiManager 取） */
    fun doLogin(form: LoginForm, meta: LoginMeta, clientIP: String): SrunResult {
        val username = if (form.userType.isNotEmpty()) "${form.username}@${form.userType}" else form.username

        val token = getChallenge(username, clientIP)

        val info = SrunCrypto.encryptedInfo(form, meta, clientIP, token)
        val md5Hex = SrunCrypto.hmacMd5Hex(token, form.password)
        val chksum = SrunCrypto.chksum(token, username, md5Hex, meta.acid, clientIP, meta.n, meta.type, info)

        val query = linkedMapOf(
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
            "double_stack" to (if (meta.doubleStack) "1" else "0"),
        )
        val res = request("cgi-bin/srun_portal", query)
        val error = res["error"] as? String ?: throw java.io.IOException("no error field in login response: $res")
        if (error == "ok") {
            val ip = (res["client_ip"] as? String) ?: clientIP
            return SrunResult.Ok(ip)
        }
        return SrunResult.Rejected(error, res.toString())
    }

    /** 登出（深澜标准：action=logout + 账号/域/ip；最小可用实现） */
    fun doLogout(form: LoginForm, meta: LoginMeta, clientIP: String): SrunResult {
        val username = if (form.userType.isNotEmpty()) "${form.username}@${form.userType}" else form.username
        val query = linkedMapOf(
            "action" to "logout",
            "username" to username,
            "ac_id" to meta.acid,
            "ip" to clientIP,
        )
        val res = request("cgi-bin/srun_portal", query)
        val error = res["error"] as? String ?: throw java.io.IOException("no error field in logout response: $res")
        return if (error == "ok") SrunResult.Ok(null) else SrunResult.Rejected(error, res.toString())
    }
}