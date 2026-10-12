package org.xjtulogin.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

data class NetInfo(
    /** 有活跃网络（可能是有线/移动数据） */
    val connected: Boolean,
    /** 活跃网络是 WiFi */
    val wifi: Boolean,
    /** 当前 SSID；null = 无 WiFi 或读不到（缺定位权限时会被系统打码） */
    val ssid: String?,
    val ipv4: String?,
) {
    val ssidText: String get() = ssid ?: if (wifi) "未知 WiFi" else "无 WiFi"
}

object WifiKit {

    /**
     * 统一走 ConnectivityManager（activeNetwork + LinkProperties）：
     * 这是 Android 10+ 的正路，WifiManager.connectionInfo 在部分 ROM 上会给出过期的 IP/SSID。
     * SSID 仍需定位权限，拿不到就返回 null —— 调用方会「按未知网络继续尝试」而不是直接跳过。
     */
    fun snapshot(context: Context): NetInfo {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = if (net != null && cm != null) cm.getNetworkCapabilities(net) else null
        val lp = if (net != null && cm != null) cm.getLinkProperties(net) else null
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ipv4 = lp?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress
        val ssid = if (wifi) readSsid(app, caps) else null
        return NetInfo(connected = net != null, wifi = wifi, ssid = ssid, ipv4 = ipv4)
    }

    private fun readSsid(ctx: Context, caps: NetworkCapabilities?): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps?.transportInfo
            if (info is WifiInfo) normalize(info.ssid)?.let { return it }
        }
        return try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            normalize(wm?.connectionInfo?.ssid)
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalize(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")
        if (s.isNullOrBlank()) return null
        if (s.equals("<unknown ssid>", true) || s == "0x") return null
        return s
    }
}
