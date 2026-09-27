package org.xjtulogin.app

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress

object WifiKit {
    private fun wm(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** 当前连接 SSID（去引号；无连接或不可读返回 null） */
    fun currentSsid(context: Context): String? {
        return try {
            val info = wm(context).connectionInfo ?: return null
            info.ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (_: Exception) {
            null
        }
    }

    /** 当前 WiFi IPv4（如 10.18.17.26）；失败返回 null */
    fun currentIpv4(context: Context): String? {
        return try {
            val ip = wm(context).connectionInfo.ipAddress ?: return null
            val bytes = byteArrayOf(
                (ip and 0xFF).toByte(),
                ((ip shr 8) and 0xFF).toByte(),
                ((ip shr 16) and 0xFF).toByte(),
                ((ip shr 24) and 0xFF).toByte(),
            )
            InetAddress.getByAddress(bytes).hostAddress
        } catch (_: Exception) {
            null
        }
    }
}