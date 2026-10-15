// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
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

    // ------------------------------------------------------------ 原脚本探测项的等价物

    private fun wifi(ctx: Context): WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** WiFi 开关状态；读不到返回 null */
    fun isWifiEnabled(ctx: Context): Boolean? = try {
        wifi(ctx)?.isWifiEnabled
    } catch (_: Throwable) {
        null
    }

    /** 「扫描始终可用」（原脚本扫到就连接的前提；关着时 WiFi 关闭就扫不到） */
    fun isScanAlwaysAvailable(ctx: Context): Boolean? = try {
        wifi(ctx)?.isScanAlwaysAvailable
    } catch (_: Throwable) {
        null
    }

    /** 当前连接（本机 API 探测；读不到 SSID 时返回 null —— 与脚本「grep 不到就算没连」一致） */
    fun connectedSsid(ctx: Context): String? = try {
        @Suppress("DEPRECATION")
        normalize(wifi(ctx)?.connectionInfo?.ssid)
    } catch (_: Throwable) {
        null
    }

    /**
     * 扫描结果里的 SSID 列表。
     *
     * 需要定位权限（Android 10+ 还要定位开关打开）；拿不到时返回 **null**（区别于「扫到 0 个」）。
     * 这正是原脚本 `termux-wifi-scaninfo` 的那条路。
     */
    fun scanSsids(ctx: Context): List<String>? = try {
        @Suppress("DEPRECATION")
        val results = wifi(ctx)?.scanResults ?: return null
        results.mapNotNull { normalize(it.SSID) }.distinct()
    } catch (_: Throwable) {
        null
    }

    /**
     * 把目标网络登记为「网络建议」（API 29+）：一次登记，之后系统自己在范围内自动连。
     *
     * 这是**不需要任何特权**的那条自动连接路子（代价：首次系统会弹一次「允许」，见 README）。
     * API 29 以下退回老 API（`addNetwork` + `enableNetwork`，那时还允许普通 App 改网络配置）。
     */
    @Suppress("DEPRECATION")
    fun suggestNetwork(ctx: Context, ssid: String, password: String? = null): Boolean {
        val wm = wifi(ctx) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
                if (!password.isNullOrEmpty()) builder.setWpa2Passphrase(password)
                val status = wm.addNetworkSuggestions(listOf(builder.build()))
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                    status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            } else {
                val cfg = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (password.isNullOrEmpty()) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        preSharedKey = "\"$password\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    }
                }
                val id = wm.addNetwork(cfg)
                if (id < 0) false else wm.enableNetwork(id, true) && wm.reconnect()
            }
        } catch (_: Throwable) {
            false
        }
    }
}
