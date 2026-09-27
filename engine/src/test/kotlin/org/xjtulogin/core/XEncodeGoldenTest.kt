package org.xjtulogin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XEncodeGoldenTest {

    private fun loadGolden(): Pair<List<Map<String, Any?>>, Map<String, Any?>> {
        val text = javaClass.getResourceAsStream("/golden.json")!!
            .bufferedReader().use { it.readText() }
        val obj = MiniJson.parseObject(text)
        @Suppress("UNCHECKED_CAST")
        val vectors = obj["vectors"] as? List<Map<String, Any?>> ?: emptyList()
        val checksum = obj["checksum"] as? Map<String, Any?> ?: emptyMap()
        return vectors to checksum
    }

    @Test
    fun goldenVectorsMatch() {
        val (vectors, _) = loadGolden()
        assertTrue(vectors.isNotEmpty(), "golden vectors file must not be empty")
        for (v in vectors) {
            val str = v["str"] as String
            val key = v["key"] as String
            val want = v["xen"] as String
            val got = SrunB64.encode(XEncode.xEncode(str, key))
            assertEquals(want, got, "B64(XEncode(${str.take(20)}... , key=${key}))")
        }
    }

    @Test
    fun emptyStringEdge() {
        assertEquals("", SrunB64.encode(XEncode.xEncode("", "12345678")))
        assertEquals("", SrunB64.encode(XEncode.xEncode("", "")))
    }

    @Test
    fun checksumGoldenCase() {
        val (_, checksum) = loadGolden()
        val token = checksum["token"] as String
        val username = checksum["username"] as String
        val password = checksum["password"] as String
        val acid = checksum["acid"] as String
        val ip = checksum["ip"] as String
        val n = checksum["n"] as String
        val type = checksum["type"] as String
        val infoWant = checksum["info"] as String
        val hmacWant = checksum["hmac"] as String
        val chksumWant = checksum["chksum"] as String

        val infoGot = "{SRBX1}" + SrunB64.encode(XEncode.xEncode(
            SrunCrypto.infoJson(username, password, ip, acid, "srun_bx1"), token))
        assertEquals(infoWant, infoGot, "EncryptedInfo")
        assertEquals(hmacWant, SrunCrypto.hmacMd5Hex(token, password), "hmac-md5")
        assertEquals(chksumWant, SrunCrypto.chksum(token, username, hmacWant, acid, ip, n, type, infoGot), "chksum(with-tailing-newline)")
    }
}