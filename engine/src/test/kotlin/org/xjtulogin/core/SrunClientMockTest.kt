package org.xjtulogin.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 本地 mock 网关：模拟 get_challenge / srun_portal / rad_user_info 三端点（零网络） */
class SrunClientMockTest {

    private lateinit var server: HttpServer
    private lateinit var client: SrunClient
    private var portalHandler: (Map<String, String>) -> String = { _ -> """{"error":"ok","client_ip":"10.0.0.5"}""" }
    private val seenLogin: MutableMap<String, String> = linkedMapOf()

    private fun respond(ex: HttpExchange, body: String) {
        val b = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun parseQuery(q: String?): Map<String, String> {
        if (q == null) return emptyMap()
        // 注意：com.sun.net.httpserver 的 requestURI.query 已被 HttpServer 解码一次
        // （真实 PHP 网关同样只解码一次），此处直接取值，绝不再二次 URL 解码
        return q.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
    }

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cgi-bin/get_challenge") { ex ->
            val q = parseQuery(ex.requestURI.query)
            val cb = q["callback"] ?: "jQuery"
            respond(ex, "$cb(" + """{"challenge":"0123456789abcdef0123456789abcdef","error":"ok"}""" + ")")
        }
        server.createContext("/cgi-bin/srun_portal") { ex ->
            val q = parseQuery(ex.requestURI.query)
            val cb = q["callback"] ?: "jQuery"
            when (q["action"]) {
                "login" -> {
                    seenLogin.putAll(q)
                    respond(ex, "$cb(" + portalHandler(q) + ")")
                }
                "logout" -> respond(ex, "$cb(" + """{"error":"ok"}""" + ")")
                else -> respond(ex, "$cb(" + """{"error":"unknown action"}""" + ")")
            }
        }
        server.createContext("/cgi-bin/rad_user_info") { ex ->
            val cb = parseQuery(ex.requestURI.query)["callback"] ?: "jQuery"
            respond(ex, "$cb(" + """{"error":"ok","client_ip":"10.0.0.5","online_ip":"10.0.0.5"}""" + ")")
        }
        server.start()
        client = SrunClient(baseUrl = "http://127.0.0.1:${server.address.port}/", timeoutMs = 3000)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private val form = LoginForm(domain = "10.6.18.2", username = "2300000000", password = "sample-pass-0000")

    @Test
    fun doLoginSuccess() {
        val r = client.doLogin(form, LoginMeta(), "10.18.17.26")
        assertTrue(r is SrunResult.Ok, "expect Ok, got $r")
        assertEquals("10.0.0.5", (r as SrunResult.Ok).ip)
    }

    @Test
    fun loginSubmitsWellFormedParams() {
        client.doLogin(form, LoginMeta(), "10.18.17.26")
        assertEquals("login", seenLogin["action"])
        assertEquals("2300000000", seenLogin["username"])
        assertEquals("1", seenLogin["ac_id"])
        assertEquals("200", seenLogin["n"])
        assertEquals("1", seenLogin["type"])
        assertEquals("0", seenLogin["double_stack"])
        // 三个加密字段的形态
        assertTrue(seenLogin["password"]!!.startsWith("{MD5}"), "password should be {MD5}hex, got ${seenLogin["password"]}")
        assertTrue(seenLogin["info"]!!.startsWith("{SRBX1}"), "info should be {SRBX1}b64, got ${seenLogin["info"]}")
        assertTrue(seenLogin["chksum"]!!.endsWith("\n"), "chksum must end with newline (Go %x\\n)")
        // chksum 内部一致性：用提交值重算
        val recomputed = SrunCrypto.chksum(
            "0123456789abcdef0123456789abcdef", seenLogin["username"]!!,
            seenLogin["password"]!!.removePrefix("{MD5}"), seenLogin["ac_id"]!!,
            "10.18.17.26", seenLogin["n"]!!, seenLogin["type"]!!, seenLogin["info"]!!
        )
        assertEquals(recomputed, seenLogin["chksum"])
    }

    @Test
    fun loginStatusOnline() {
        val (online, ip) = client.loginStatus()
        assertTrue(online)
        assertEquals("10.0.0.5", ip)
    }

    @Test
    fun doLogoutSuccess() {
        val r = client.doLogout(form, LoginMeta(), "10.18.17.26")
        assertTrue(r is SrunResult.Ok, "expect Ok on logout, got $r")
    }

    @Test
    fun rejectedErrorPassesThrough() {
        portalHandler = { _ -> """{"error":"E2531: 账号欠费，请及时充值","client_ip":"10.0.0.5"}""" }
        val r = client.doLogin(form, LoginMeta(), "10.18.17.26")
        assertTrue(r is SrunResult.Rejected, "expect Rejected, got $r")
        assertEquals("E2531: 账号欠费，请及时充值", (r as SrunResult.Rejected).error)
    }
}