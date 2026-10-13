// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

data class NetInfo(
    /** 有可用网络（可能是有线/移动数据/VPN） */
    val connected: Boolean,
    /** 我们认定的 WiFi 网络可用（VPN 活着时也要求底层 WiFi 在） */
    val wifi: Boolean,
    /** 当前 SSID；null = 无 WiFi 或读不到（缺定位权限时会被系统打码） */
    val ssid: String?,
    val ipv4: String?,
    /** VPN（覆盖网等）正在占用默认网络 */
    val vpnActive: Boolean = false,
    /** 明确的 WiFi 网络对象：交给 SrunClient 绑 socket，绕开 VPN 抢占的路由 */
    val wifiNetwork: Network? = null,
) {
}

object WifiKit {

    /**
     * VPN 存活时 `activeNetwork` 是 VPN 隧道（TRANSPORT_VPN），
     * 所以不能只看 activeNetwork —— 否则开着 VPN 就会误判「不是 WiFi」而永远不登录，
     * 而且拿到的 IPv4 会是隧道地址（172.31.x.x），网关根本认不出。
     * 这里显式挑出 TRANSPORT_WIFI 那张网卡：SSID / IP / socket 绑定都用它。
     */
    fun snapshot(context: Context): NetInfo {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetInfo(false, false, null, null)

        val active = cm.activeNetwork
        val activeCaps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val vpnActive = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // allNetworks 自 API 31 起标记 deprecated（推荐 registerNetworkCallback 持续跟踪），
        // 但它是唯一能"一次性列出全部网络"的接口，正是这里绕过 VPN 需要的语义。
        @Suppress("DEPRECATION")
        val wifiNet = runCatching {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrNull()

        val chosen = wifiNet ?: active
        val caps = chosen?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val lp = chosen?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ipv4 = lp?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress
        val ssid = if (wifi) readSsid(app, caps) else null
        return NetInfo(
            connected = chosen != null,
            wifi = wifi,
            ssid = ssid,
            ipv4 = ipv4,
            vpnActive = vpnActive,
            wifiNetwork = wifiNet,
        )
    }

    @Suppress("DEPRECATION")
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
