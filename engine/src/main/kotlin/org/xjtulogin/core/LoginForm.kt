// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 账号信息（协议 form 字段）；[userType] 为身份/运营商后缀，西交校园网为 stu */
data class LoginForm(
    val domain: String,
    val username: String,
    val userType: String = "",
    val password: String,
)

/** 提交参数（协议 meta 字段）；默认值即西交校园网在用的那一套 */
data class LoginMeta(
    val n: String = "200",
    val type: String = "1",
    val acid: String = "1",
    val enc: String = "srun_bx1",
    val os: String = "Windows 2000",
    val name: String = "losedows",
    val infoPrefix: String = "SRBX1",
    val doubleStack: Boolean = false,
)

/** 登录/登出结果 */
sealed class SrunResult {
    /** 网关 error == "ok" */
    data class Ok(val ip: String?, val extra: Map<String, String> = emptyMap()) : SrunResult()

    /** 网关返回的错误串（如 "login_error"、"E2531: 账号欠费"） */
    data class Rejected(val error: String, val raw: String) : SrunResult()
}

/**
 * 登录三段摘要（规范见 docs/PROTOCOL.md §2）：
 *  - `password` = `"{MD5}" + hmac-md5(key = challenge, msg = 明文密码)` 的十六进制；
 *  - `info`     = `"{SRBX1}" + 变体 base64(XEncode(infoJson, challenge))`；
 *  - `chksum`   = `sha1(challenge 分隔拼接) `的十六进制，**末尾带换行**。
 */
object SrunCrypto {

    /** `{MD5}` 字段的实际内容：以 challenge 为密钥对明文密码做 hmac-md5 */
    fun hmacMd5Hex(token: String, password: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** sha1 十六进制 + 换行（网关接受这种形态，主流实现一致） */
    fun sha1HexWithNewline(content: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + "\n"
    }

    /** info 的 JSON；键序由实现自定（网关按 JSON 解析），这里用字典序 */
    fun infoJson(username: String, password: String, ip: String, acid: String, enc: String): String =
        "{\"acid\":\"$acid\",\"enc_ver\":\"$enc\",\"ip\":\"$ip\",\"password\":\"$password\",\"username\":\"$username\"}"

    /** `{SRBX1}` + 变体 base64(XEncode(infoJson, token)) */
    fun encryptedInfo(form: LoginForm, meta: LoginMeta, ip: String, token: String): String {
        val prefix = if (meta.infoPrefix.isEmpty()) "" else "{${meta.infoPrefix}}"
        val info = infoJson(form.username, form.password, ip, meta.acid, meta.enc)
        return prefix + SrunB64.encode(XEncode.xEncode(info, token))
    }

    /** chksum = sha1(token + username + token + md5 + token + acid + token + ip + token + n + token + type + token + info) */
    fun chksum(
        token: String,
        username: String,
        md5Hex: String,
        acid: String,
        ip: String,
        n: String,
        type: String,
        info: String,
    ): String = sha1HexWithNewline(
        token + username + token + md5Hex + token + acid + token + ip + token + n + token + type + token + info
    )
}
