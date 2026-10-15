// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context

/**
 * 「看 WiFi + 修 WiFi」通道。
 *
 * 原脚本在这件事上有两条路：
 *  - 探测（已连哪个 SSID / 扫到了谁）：`termux-wifi-*`，失败回退 `adb shell dumpsys wifi`
 *  - 动作（连网 / 主动扫描）：`adb shell cmd wifi connect-network|start-scan`
 *
 * App 里对应：
 *  - 探测：[NativeChannel]（本机 `WifiManager`，最省电，不需要特权）
 *  - 动作：[ShellChannel]（经 Shizuku 拿 shell 身份跑 `cmd wifi …`；`set-wifi-enabled` 只能这样开）
 *
 * 拿不到 shell 身份时，动作降级为 [NativeChannel] 的「网络建议」或直接放弃 —— 由 [WifiChannels] 统一决定。
 */
interface WifiChannel {
    val label: String

    /** 能否主动开关 WiFi / 主动连网（= 有没有 shell 身份） */
    val canControl: Boolean

    fun isWifiEnabled(): Boolean?

    fun connectedSsid(): String?

    /** 扫描结果里的 SSID；**null = 探测不了**（无定位权限/命令不可用），空列表 = 确实没扫到 */
    fun scanSsids(): List<String>?

    fun startScan(): Boolean

    fun connect(ssid: String, password: String?): Boolean

    fun setWifiEnabled(on: Boolean): Boolean
}

/** 本机 API 通道：只读探测 + 「网络建议」式连网（无需特权） */
class NativeChannel(private val ctx: Context) : WifiChannel {
    override val label: String = "本机 API"
    override val canControl: Boolean = false

    override fun isWifiEnabled(): Boolean? = WifiKit.isWifiEnabled(ctx)
    override fun connectedSsid(): String? = WifiKit.connectedSsid(ctx)
    override fun scanSsids(): List<String>? = WifiKit.scanSsids(ctx)

    /** 本机 `startScan()` 自 API 28 起被限流、且要定位权限，收益极低 —— 交给 shell 通道做 */
    override fun startScan(): Boolean = false

    /** 登记「网络建议」：之后由系统自己在范围内自动连（首个建议会弹一次系统授权） */
    override fun connect(ssid: String, password: String?): Boolean =
        WifiKit.suggestNetwork(ctx, ssid, password)

    /** 普通 App 完全做不到（Android 10+ 一律失败），只能走 shell 或让用户点通知 */
    override fun setWifiEnabled(on: Boolean): Boolean = false
}

/** Shizuku（ADB shell 身份）通道：原脚本 `$ADB shell …` 的等价物 */
class ShellChannel(private val ctx: Context) : WifiChannel {
    override val label: String = "Shizuku(shell)"
    override val canControl: Boolean = true

    override fun isWifiEnabled(): Boolean? {
        val r = ShizukuShell.run(ctx, "cmd wifi status") ?: return null
        if (r.ok) {
            r.output.lineSequence().firstOrNull { it.startsWith("Wifi is ") }?.let {
                return it.contains("is enabled")
            }
        }
        // 老 ROM 没有 `cmd wifi status`：退回原脚本那招（dumpsys）
        val d = ShizukuShell.run(ctx, "dumpsys wifi") ?: return null
        return when {
            d.output.contains("Wi-Fi is enabled") -> true
            d.output.contains("Wi-Fi is disabled") -> false
            else -> null
        }
    }

    override fun connectedSsid(): String? {
        val r = ShizukuShell.run(ctx, "cmd wifi status")
        if (r != null && r.ok) {
            CONNECTED_RE.find(r.output)?.groupValues?.getOrNull(1)?.let { return it }
        }
        // 原脚本的解析：`mWifiInfo SSID: "XJTU_STU"`（本机实测 Android 12 仍存在）
        val d = ShizukuShell.run(ctx, "dumpsys wifi") ?: return null
        return DUMP_SSID_RE.find(d.output)?.groupValues?.getOrNull(1)
    }

    override fun scanSsids(): List<String>? {
        val r = ShizukuShell.run(ctx, "cmd wifi list-scan-results") ?: return null
        if (!r.ok) return null
        val list = mutableListOf<String>()
        for (raw in r.output.lineSequence()) {
            val cols = raw.trim().split(COL_SPLIT)
            // BSSID / Frequency / RSSI / Age / SSID / Flags
            if (cols.size >= 5 && BSSID_RE.matches(cols[0])) {
                val ssid = cols[4].trim()
                if (ssid.isNotEmpty()) list.add(ssid)
            }
        }
        return list.distinct()
    }

    override fun startScan(): Boolean = ShizukuShell.run(ctx, "cmd wifi start-scan")?.ok == true

    override fun connect(ssid: String, password: String?): Boolean {
        val sec = if (password.isNullOrEmpty()) "open" else "wpa2"
        val cmd = buildString {
            append("cmd wifi connect-network ").append(shQuote(ssid)).append(' ').append(sec)
            if (!password.isNullOrEmpty()) append(' ').append(shQuote(password))
        }
        return ShizukuShell.run(ctx, cmd)?.ok == true
    }

    override fun setWifiEnabled(on: Boolean): Boolean {
        val want = if (on) "enabled" else "disabled"
        ShizukuShell.run(ctx, "cmd wifi set-wifi-enabled $want")?.let { if (it.ok) return true }
        // Android 11 之前只有 svc（AOSP 里 svc wifi 现在就是上面那条的壳）
        val legacy = if (on) "enable" else "disable"
        return ShizukuShell.run(ctx, "svc wifi $legacy")?.ok == true
    }

    private companion object {
        val CONNECTED_RE = Regex("Wifi is connected to \"([^\"]+)\"")
        val DUMP_SSID_RE = Regex("mWifiInfo SSID: \"([^\"]+)\"")
        val BSSID_RE = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")
        val COL_SPLIT = Regex("\\s{2,}")

        /** shell 单引号包裹（SSID 里可能有空格/中文/引号） */
        fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

/**
 * 通道选择器：把「探测」与「动作」按可用性路由，并在降级时说清楚用的是哪条路。
 *
 * 探测优先走本机 API（省电、无权限门槛以外的依赖）；本机读不到 SSID 时才去问 shell。
 * 动作只有 shell 能做（`set-wifi-enabled` 尤其如此）；没有 shell 时连网降级成「网络建议」。
 */
class WifiChannels(ctx: Context, useShizuku: Boolean) {

    private val app = ctx.applicationContext
    private val native = NativeChannel(app)

    /** 上一次探测是否走了 shell（= 原脚本的「刷新 dumpsys」，要计高耗电） */
    @Volatile var lastReadViaShell = false
        private set
    /** Shizuku 可用且用户开了开关时才建立；建立后一直复用 */
    val shell: ShellChannel? =
        if (useShizuku && ShizukuShell.hasPermission()) ShellChannel(app) else null

    val canControl: Boolean get() = shell != null

    /** 当前实际生效的动作通道名（状态里显示给用户看） */
    val actionLabel: String
        get() = when {
            shell != null -> shell.label
            else -> "无（仅网络建议 / 通知提示）"
        }

    fun isWifiEnabled(): Boolean? = native.isWifiEnabled() ?: shell?.isWifiEnabled()

    fun connectedSsid(): String? {
        native.connectedSsid()?.let {
            lastReadViaShell = false
            return it
        }
        val viaShell = shell?.connectedSsid()
        lastReadViaShell = shell != null
        return viaShell
    }

    fun scanSsids(): List<String>? {
        native.scanSsids()?.let {
            lastReadViaShell = false
            return it
        }
        val viaShell = shell?.scanSsids()
        lastReadViaShell = shell != null
        return viaShell
    }

    fun startScan(): Boolean = shell?.startScan() == true

    /** 主动连网：有 shell 就直接连（原脚本行为）；否则登记网络建议 */
    fun connect(ssid: String, password: String?): Boolean =
        shell?.connect(ssid, password) ?: native.connect(ssid, password)

    fun setWifiEnabled(on: Boolean): Boolean = shell?.setWifiEnabled(on) == true
}
