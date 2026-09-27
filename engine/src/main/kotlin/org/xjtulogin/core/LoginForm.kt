package org.xjtulogin.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 登录提交参数（对应 BitSrunLoginGo LoginForm + LoginMeta） */
data class LoginForm(
    val domain: String,
    val username: String,
    val userType: String = "",
    val password: String,
)

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

/** 登录结果 */
sealed class SrunResult {
    /** res["error"] == "ok" */
    data class Ok(val ip: String?, val extra: Map<String, String> = emptyMap()) : SrunResult()

    /** 网关返回的错误字符串（如 "E0000" / "IP has been online, please logout" / 欠费提示等） */
    data class Rejected(val error: String, val raw: String) : SrunResult()
}

object SrunCrypto {
    /** {MD5}: hmac-md5(token, password) 的 hex —— 提交的 password 字段 */
    fun hmacMd5Hex(token: String, password: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** chksum 的 sha1：Go fmt.Sprintf("%x\n", bs) —— 末尾带换行！ */
    fun sha1HexWithNewline(content: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bs = md.digest(content.toByteArray(Charsets.UTF_8))
        return bs.joinToString("") { "%02x".format(it) } + "\n"
    }

    /** 登录 info 字段的 JSON（Go: json.Marshal(map[string]string) 按键字母序：acid, enc_ver, ip, password, username） */
    fun infoJson(username: String, password: String, ip: String, acid: String, enc: String): String {
        return "{\"acid\":\"$acid\",\"enc_ver\":\"$enc\",\"ip\":\"$ip\",\"password\":\"$password\",\"username\":\"$username\"}"
    }

    /** 组装 EncryptedInfo = "{SRBX1}" + B64(XEncode(infoJson, token)) */
    fun encryptedInfo(form: LoginForm, meta: LoginMeta, ip: String, token: String): String {
        val prefix = if (meta.infoPrefix.isEmpty()) "" else "{${meta.infoPrefix}}"
        val info = infoJson(form.username, form.password, ip, meta.acid, meta.enc)
        return prefix + SrunB64.encode(XEncode.xEncode(info, token))
    }

    /** chksum = sha1(token + username + token + md5 + token + acid + token + ip + token + n + token + type + token + info) + "\n" */
    fun chksum(token: String, username: String, md5Hex: String, acid: String, ip: String, n: String, type: String, info: String): String {
        return sha1HexWithNewline(
            token + username + token + md5Hex + token + acid + token + ip + token + n + token + type + token + info
        )
    }
}