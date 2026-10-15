// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.xjtulogin.core.LoginForm
import org.xjtulogin.core.LoginMeta
import org.xjtulogin.core.SrunClient
import org.xjtulogin.core.SrunResult

/**
 * 保活前台服务 —— 状态机**逐条对齐原 Termux 脚本 `keep_srun_v2.6.sh`**。
 *
 * 原脚本三态：
 *  - `disconnected`：宿舍网优先（连着→room；扫到→连上→room）→ 校园网已连就登录（配置 18/21，
 *    每条 `run_with_timeout 10 ./bitsrun`）→ 只扫到就连它（`cmd wifi connect-network`）→ 主动扫描 →
 *    `sleep t`（t 初值 5，登录全失败 -1，尝试连网 -2）
 *  - `connected`：`sleep 5` → 查宿舍网（命中即回 disconnected）→ 续期（超时/失败即回 disconnected）
 *  - `room`：`sleep 5` → 宿舍网还在吗（掉线即回 disconnected）
 *
 * 本服务的对应关系：
 *  - `./bitsrun --config N` → 引擎 `SrunClient`（N = 网关 IP 倒数第二段，见 [configNo]）
 *  - `$ADB shell cmd wifi …` → [WifiChannels]（Shizuku 拿 shell 身份；没有就降级）
 *  - `termux-wifi-*`/`dumpsys` 探测 → [WifiKit]（本机 API）
 *  - `log()` → [LogKit]（同样的 `HH:MM:SS [LEVEL] msg`、同样的等级与配色）
 *
 * 保活链条（原脚本之外的工程补充，缺一不可）：
 *  1. 明文 HTTP 放行（res/xml/network_security_config.xml）；
 *  2. 前台服务 + START_STICKY + 常驻通知；
 *  3. 网络变化立即重检（唤醒等待，不等下一个 sleep 到点）；
 *  4. 心跳落盘（进程真死了，回前台能立刻发现并自愈）；
 *  5. 电池优化白名单。
 */
class LoginService : Service() {

    companion object {
        const val CHANNEL_ID = "keepalive"
        const val NOTIF_ID = 1001
        const val BATTERY_NOTIF_ID = 1002

        /**
         * XJTU 全网实测只有这 4 个 srun 端点（2026-10-12 从校园网内扫 10.6.0-254.2 / 10.69.x.2 / 10.172.x.2 得出）：
         * 10.6.8.2 是总门户（老版本响应形态），18.2 / 21.2 / 27.2 是各区域 AC。
         * 用户配置之外自动兜底试这些 —— 食堂等区域归不同的 AC 服务，发给错的 AC 只会得到 login_error。
         */
        private val BUILTIN_GATEWAYS = listOf("10.6.8.2", "10.6.18.2", "10.6.21.2", "10.6.27.2")

        /** 原脚本的「配置 N」= 网关 IP 倒数第二段（10.6.**18**.2 → 18） */
        private val VALID_CFG = Regex("^10\\.6\\.(\\d+)\\.2$")

        /** shell 单引号包裹（SSID/口令里可能有空格、中文、引号） */
        private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        const val ACTION_START = "org.xjtulogin.app.action.START"
        const val ACTION_STOP = "org.xjtulogin.app.action.STOP"
        const val ACTION_CHECK_NOW = "org.xjtulogin.app.action.CHECK_NOW"

        /** 单次登录：只跑一遍 `disconnected`，成败即止（不常驻、不续期） */
        const val ACTION_ONE_SHOT = "org.xjtulogin.app.action.ONE_SHOT"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = ctx.getString(R.string.notif_channel_desc)
            ch.setShowBadge(false)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }

        /** 启动/唤醒保活（已在跑则立刻插一次检测） */
        fun start(ctx: Context) {
            val i = Intent(ctx, LoginService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Throwable) {
                // Android 12+ 后台启动前台服务限制：静默失败，回前台时 MainActivity 会自愈
            }
        }

        /** 停止保活并关掉开关 */
        fun stop(ctx: Context) {
            AppStore.save(ctx, AppStore.load(ctx).copy(serviceEnabled = false))
            StatusStore.setStatus(ctx, "已停止", "手动停止保活", StatusLevel.IDLE)
            try {
                ctx.stopService(Intent(ctx, LoginService::class.java))
            } catch (_: Throwable) {
            }
        }

        /** 立即检测一次（已启动 = 插队检测；未启动 = 只检测这一轮） */
        fun checkNow(ctx: Context) {
            val i = Intent(ctx, LoginService::class.java).setAction(ACTION_CHECK_NOW)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Throwable) {
            }
        }

        /** 单次登录（不常驻）—— 复刻原脚本 `disconnected` 那一遍 */
        fun oneShot(ctx: Context) {
            val i = Intent(ctx, LoginService::class.java).setAction(ACTION_ONE_SHOT)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Throwable) {
            }
        }
    }

    private val gate = Object()
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var oneShot = false
    @Volatile private var wakeRequested = false
    @Volatile private var lastWakeAt = 0L
    /** 本进程已被系统拒绝过「绑网卡」（EPERM）⇒ 之后一律走默认路由，不再白试 */
    @Volatile private var bindBlocked = false
    /** 实际走通的 VPN 路由策略（用于状态展示） */
    @Volatile private var vpnStrategy: BindMode? = null
    /** 原脚本的 `successful_config`：上次登录/续期成功的那对（网关+账号） */
    @Volatile private var successful: LoginTarget? = null
    /** 原脚本的 `refresh_count`（高耗电通知计数） */
    private var refreshCount = 0
    /** 原脚本的 `last_scan_time` */
    private var lastScanAt = 0L
    /** 上一次扫描不可用的提示只打一次，别刷屏 */
    private var scanUnavailableLogged = false

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        registerNetworkWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForegroundCompat(getString(R.string.status_checking))

        when (intent?.action) {
            ACTION_STOP -> {
                AppStore.save(this, AppStore.load(this).copy(serviceEnabled = false))
                log(LogLevel.WARN, "检测到手动终止 (Ctrl+C)，脚本退出")
                stopForeground(STOP_FOREGROUND_REMOVE)
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_ONE_SHOT -> {
                oneShot = true
                wake(force = true)
            }
            ACTION_CHECK_NOW -> {
                // 未启动时也允许「只检测这一轮」
                wake(force = true)
            }
            else -> {
                wake(force = true)
            }
        }

        val cfg = AppStore.load(this)
        if (!running) {
            running = true
            if (intent?.action != ACTION_ONE_SHOT) oneShot = !cfg.serviceEnabled
            worker = Thread({ loop() }, "login-loop").also { it.start() }
        } else {
            wake(force = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wake(force = true)
        netCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            } catch (_: Throwable) {
            }
        }
        netCallback = null
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 日志

    private fun log(level: LogLevel, msg: String) {
        StatusStore.append(this, level, msg)
        // 日志是给别人看的（贴群里求助），状态卡也要跟着动
        notifyStatus()
    }

    /** 原脚本 `log_adb_prefix()`：先打 `HH:MM:SS [INFO] ADB: `，再让命令输出跟在后面 */
    private fun logAdb(cmd: String, out: ShizukuShell.Result?) {
        val tail = when {
            out == null -> "（shizuku 不可用）"
            out.ok -> ""
            else -> " (exit=${out.exit})"
        }
        StatusStore.append(this, LogLevel.INFO, "ADB: $cmd$tail")
        if (out != null && out.output.isNotBlank()) {
            StatusStore.append(this, LogLevel.DEBG, out.output.trim().take(400).replace('\n', ' '))
        }
        notifyStatus()
    }

    /** 走 shell 探测时的「高耗电」提示（原脚本 refresh_wifi_cache 的等价物） */
    private fun tickRefresh(where: String) {
        refreshCount++
        val cfg = AppStore.load(this)
        if (!cfg.batteryWarning) return
        if (refreshCount == 1 || refreshCount % 100 == 0) {
            log(LogLevel.WARN, "正在刷新dumpsys (高耗电)")
            notifyBatteryWarning(refreshCount, where)
        }
    }

    // ---------------------------------------------------------------- 状态机

    private enum class State { DISCONNECTED, CONNECTED, ROOM }

    /** 原脚本的 `successful_config` */
    private data class LoginTarget(val gateway: Gateway, val account: Account)

    /** 原脚本的「配置 N」= 网关 IP 倒数第二段（10.6.**18**.2 → 18） */
    private fun configNo(gw: Gateway): String {
        val host = hostOf(gw.domain)
        VALID_CFG.find(host)?.let { return it.groupValues[1] }
        return host
    }

    private fun loop() {
        val boot = AppStore.load(this)

        log(LogLevel.INFO, "脚本已启动")
        if (boot.wifiScan) {
            log(LogLevel.INFO, "Wi‑Fi 扫描间隔: ${boot.scanIntervalSec}s")
        } else {
            log(LogLevel.WARN, "Wi‑Fi 扫描已禁用")
        }
        if (!boot.batteryWarning) {
            log(LogLevel.WARN, "电池消耗通知已关闭")
        }

        // 原脚本的前置检查（找不到目录 / 找不到 bitsrun）⇒ 这里 = 账号与网关
        if (boot.accounts.none { it.enabled && it.username.isNotBlank() } ||
            boot.gateways.none { it.enabled && it.domain.isNotBlank() }
        ) {
            log(LogLevel.FATA, "缺少可用的账号/网关配置（请在设置里添加）")
            StatusStore.setStatus(
                this, "缺少配置", "请在设置里添加并勾选账号与网关",
                StatusLevel.WARN, null, null
            )
            finishRun()
            return
        }

        // 用户开了「自动开启 WiFi」：开启保活时开一次（原脚本没这条，是本 App 的补充）
        autoEnableWifiOnce(boot)

        var state = State.DISCONNECTED
        while (running) {
            val cfg = AppStore.load(this)
            val next = when (state) {
                State.DISCONNECTED -> runDisconnected(cfg)
                State.CONNECTED -> runConnected(cfg)
                State.ROOM -> runRoom(cfg)
            }
            if (next == null) break
            state = next
        }
        finishRun()
    }

    private fun finishRun() {
        running = false
        if (oneShot) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /** 原脚本没有，但用户要求：开启保活时若 WiFi 关着，自动打开一次 */
    private fun autoEnableWifiOnce(cfg: AppConfig) {
        if (!cfg.autoEnableWifi) return
        val ch = WifiChannels(this, cfg.useShizuku)
        val enabled = ch.isWifiEnabled() ?: return
        if (enabled) return
        log(LogLevel.INFO, "WiFi 处于关闭状态，尝试自动打开")
        if (ch.setWifiEnabled(true)) {
            log(LogLevel.INFO, "WiFi 已打开（cmd wifi set-wifi-enabled enabled）")
        } else {
            log(LogLevel.WARN, "自动打开 WiFi 失败：需要 Shizuku 授权（设置页可授权），或手动打开 WiFi")
        }
    }

    /** 原脚本 `disconnected` 状态；返回下一个状态，null = 结束 */
    private fun runDisconnected(cfg: AppConfig): State? {
        log(LogLevel.STAT, "进入未连接状态")
        val ch = WifiChannels(this, cfg.useShizuku)
        registerSuggestion(cfg, ch)

        while (running) {
            var t = cfg.baseLoopSec.coerceIn(1, 3600)

            // 1) 宿舍 Wi‑Fi 已连接
            val nowConnected = ch.connectedSsid()
            if (cfg.rooms.any { ssidKey(it.ssid) == ssidKey(nowConnected) }) {
                return State.ROOM
            }

            // 2) 宿舍 Wi‑Fi 扫描到但未连接 → 主动连（原脚本：cmd wifi connect-network "$ssid" wpa2 "$pass"）
            val scanned = scanView(cfg, ch)
            val room = cfg.rooms.firstOrNull { r -> scanned?.any { ssidKey(it) == ssidKey(r.ssid) } == true }
            if (room != null) {
                connectTo(ch, room.ssid, room.password.ifBlank { null })
                return State.ROOM
            }

            // 3) 校园网处理
            val onCampus = cfg.targetSsids.any { ssidKey(it) == ssidKey(nowConnected) } ||
                (cfg.targetSsids.isEmpty() && nowConnected != null)
            if (onCampus) {
                log(LogLevel.INFO, "校园网已连接，尝试登录")
                val login = tryLogins(cfg, ch, nowConnected ?: "")
                if (login != null) {
                    successful = login
                    return State.CONNECTED
                }
                log(LogLevel.WARN, "所有配置均失败")
                t -= 1
            } else if (cfg.targetSsids.any { t0 -> scanned?.any { ssidKey(it) == ssidKey(t0) } == true }) {
                val target = cfg.targetSsids.first { t0 -> scanned?.any { ssidKey(it) == ssidKey(t0) } == true }
                log(LogLevel.INFO, "扫描到校园网，尝试连接")
                connectTo(ch, target, null)
                t -= 2
            } else if (scanned == null) {
                // 探测不了（无定位权限且没有 shell 身份）：原脚本此时同样是「当作没扫到」
                log(LogLevel.INFO, "未检测到校园网")
                log(LogLevel.DEBG, "（扫不到原因：本机读不到扫描结果，且没有 Shizuku shell 身份）")
            } else {
                log(LogLevel.INFO, "未检测到校园网")
            }

            // 4) 主动扫描
            maybeScan(cfg, ch)

            if (oneShot) return null
            if (!sleepInterruptible(t)) return null
        }
        return null
    }

    /** 原脚本 `connected` 状态 */
    private fun runConnected(cfg: AppConfig): State? {
        log(LogLevel.STAT, "进入校园网状态")
        val ch = WifiChannels(this, cfg.useShizuku)
        val start = System.currentTimeMillis()
        var renewals = 0

        while (running) {
            if (!sleepInterruptible(cfg.renewIntervalSec.coerceIn(1, 86400))) return null

            // 检测宿舍 Wi‑Fi（带 refresh）
            val scanned = scanView(cfg, ch, refresh = true)
            if (cfg.rooms.any { r -> scanned?.any { ssidKey(it) == ssidKey(r.ssid) } == true }) {
                val sec = (System.currentTimeMillis() - start) / 1000
                log(LogLevel.WARN, "检测到宿舍Wi‑Fi (在线${sec}s, 续期${renewals}次)")
                successful = null
                return State.DISCONNECTED
            }

            // 续期
            val target = successful
            if (target == null) {
                successful = null
                return State.DISCONNECTED
            }
            val r = attemptLogin(cfg, ch, target)
            val sec = (System.currentTimeMillis() - start) / 1000
            when (r.outcome) {
                Outcome.ONLINE -> {
                    renewals++
                    log(LogLevel.INFO, "使用配置${configNo(target.gateway)}成功续期${renewals}次，共${sec}秒")
                }
                Outcome.TIMEOUT -> {
                    log(LogLevel.ERRO, "bitsrun 续期超时 (${cfg.loginTimeoutSec}s)")
                    successful = null
                    return State.DISCONNECTED
                }
                Outcome.FAILED -> {
                    log(LogLevel.WARN, "第${renewals + 1}次续期失败")
                    if (r.detail.isNotBlank()) log(LogLevel.DEBG, r.detail)
                    successful = null
                    return State.DISCONNECTED
                }
            }
            maybeScan(cfg, ch)
        }
        return null
    }

    /** 原脚本 `room` 状态 */
    private fun runRoom(cfg: AppConfig): State? {
        log(LogLevel.STAT, "进入宿舍模式")
        val ch = WifiChannels(this, cfg.useShizuku)
        val start = System.currentTimeMillis()
        while (running) {
            if (!sleepInterruptible(cfg.renewIntervalSec.coerceIn(1, 86400))) return null
            val nowConnected = ch.connectedSsid()
            val room = cfg.rooms.firstOrNull { ssidKey(it.ssid) == ssidKey(nowConnected) }
            val sec = (System.currentTimeMillis() - start) / 1000
            if (room != null) {
                log(LogLevel.INFO, "宿舍Wi‑Fi在线: ${room.ssid} (${sec}s)")
            } else {
                log(LogLevel.WARN, "宿舍Wi‑Fi断开 (在线${sec}s)")
                return State.DISCONNECTED
            }
            maybeScan(cfg, ch)
        }
        return null
    }

    // ---------------------------------------------------------------- 动作（原脚本的 cmd wifi 调用）

    /** 原脚本每次进未连接态都会尝试 `cmd wifi connect-network`；这里是「登记网络建议」的等价动作 */
    private fun registerSuggestion(cfg: AppConfig, ch: WifiChannels) {
        if (ch.canControl) return // 有 shell 身份就直接连，不需要系统兜底
        for (s in cfg.targetSsids) {
            if (WifiKit.suggestNetwork(this, s)) {
                log(LogLevel.DEBG, "已登记「网络建议」：$s（系统会在范围内自动连接）")
            }
        }
    }

    private fun connectTo(ch: WifiChannels, ssid: String, password: String?) {
        if (ch.canControl) {
            val sec = if (password.isNullOrEmpty()) "open" else "wpa2"
            val cmd = "cmd wifi connect-network '$ssid' $sec" +
                if (password.isNullOrEmpty()) "" else " '***'"
            val r = ch.shell?.let { shell ->
                ShizukuShell.run(
                    this,
                    "cmd wifi connect-network ${shQuote(ssid)} $sec" +
                        if (password.isNullOrEmpty()) "" else " ${shQuote(password)}"
                )
            }
            logAdb(cmd, r)
        } else {
            val ok = ch.connect(ssid, password)
            log(LogLevel.DEBG, if (ok) "已登记「网络建议」：$ssid" else "登记「网络建议」失败：$ssid")
        }
    }

    private fun maybeScan(cfg: AppConfig, ch: WifiChannels) {
        if (!cfg.wifiScan) return
        val now = System.currentTimeMillis()
        if (now - lastScanAt < cfg.scanIntervalSec * 1000L) return
        if (!ch.canControl) {
            if (!scanUnavailableLogged) {
                scanUnavailableLogged = true
                log(LogLevel.WARN, "Wi‑Fi 扫描需要 Shizuku shell 身份，当前已跳过（设置页可授权）")
            }
            return
        }
        lastScanAt = now
        val ok = ch.startScan()
        log(LogLevel.INFO, if (ok) "Wi‑Fi扫描: 已执行" else "Wi‑Fi扫描: 失败")
    }

    /** 探测一次扫描结果；走 shell 时计入「高耗电」计数（原脚本 refresh_wifi_cache 的等价物） */
    private fun scanView(cfg: AppConfig, ch: WifiChannels, refresh: Boolean = false): List<String>? {
        val list = ch.scanSsids()
        if (ch.lastReadViaShell && (refresh || list != null)) tickRefresh("list-scan-results")
        return list
    }

    // ---------------------------------------------------------------- 登录（原脚本的 ./bitsrun --config N）

    private enum class Outcome { ONLINE, TIMEOUT, FAILED }

    private data class Attempt2(
        val outcome: Outcome,
        val ip: String? = null,
        val detail: String = "",
        /** 网关真的应答了（拿到响应，而不只是网络错）—— VPN 下决定要不要降级绑卡策略 */
        val answered: Boolean = false,
        val alreadyOnline: Boolean = false,
    )

    /**
     * 按「配置」顺序把登录试一遍 —— 对应原脚本：
     * ```
     * for cfg in "${CONFIGS[@]}"; do
     *     if run_with_timeout 10 ./bitsrun --config "$cfg"; then log INFO "配置${cfg}登录成功"; ...; break
     *     elif [ $? -eq 124 ]; then log ERRO "bitsrun 超时 (10s)"
     *     else sleep 0.2; fi
     * done
     * ```
     * 返回成功的那一对（= `successful_config`），全失败返回 null。
     */
    private fun tryLogins(cfg: AppConfig, ch: WifiChannels, ssid: String): LoginTarget? {
        val accounts = cfg.accounts.filter { it.enabled && it.username.isNotBlank() }
        val gateways = loginGateways(cfg)
        for (gw in gateways) {
            for (acc in accounts) {
                if (!running) return null
                val r = attemptLogin(cfg, ch, LoginTarget(gw, acc))
                when (r.outcome) {
                    Outcome.ONLINE -> {
                        log(LogLevel.INFO, "配置${configNo(gw)}登录成功")
                        if (r.detail.isNotBlank()) log(LogLevel.DEBG, r.detail)
                        AppStore.save(this, AppStore.load(this).copy(lastGoodGateway = hostOf(gw.domain)))
                        publishState(ssid, r.ip, online = true)
                        return LoginTarget(gw, acc)
                    }
                    Outcome.TIMEOUT -> log(LogLevel.ERRO, "bitsrun 超时 (${cfg.loginTimeoutSec}s)")
                    Outcome.FAILED -> {
                        if (r.detail.isNotBlank()) log(LogLevel.DEBG, r.detail)
                        sleepQuiet(200) // 原脚本失败后 sleep 0.2
                    }
                }
            }
        }
        publishState(ssid, null, online = false)
        return null
    }

    /** 配置的网关优先（顺序即优先级），再补没配过的内置端点；上次成功的排最前 */
    private fun loginGateways(cfg: AppConfig): List<Gateway> {
        val configured = cfg.gateways.filter { it.enabled && it.domain.isNotBlank() }
        val known = configured.map { hostOf(it.domain) }.toSet()
        return (configured + BUILTIN_GATEWAYS.filter { it !in known }.map { Gateway(it) })
            .sortedByDescending { hostOf(it.domain) == cfg.lastGoodGateway }
    }

    /** VPN 下的路由策略：绑 socket → 进程绑 WiFi → 不绑（默认路由） */
    private enum class BindMode { SOCKET, PROCESS, NONE }

    /**
     * 一次「查在线状态 → 不在线就登录」。整轮受 [AppConfig.loginTimeoutSec] 约束
     * （原脚本的 `run_with_timeout 10`）。
     */
    private fun attemptLogin(cfg: AppConfig, ch: WifiChannels, target: LoginTarget): Attempt2 {
        val started = System.currentTimeMillis()
        val budgetMs = cfg.loginTimeoutSec.coerceIn(2, 600) * 1000L
        val net = WifiKit.snapshot(this)
        val isOnline = runAttempt(cfg, net, target, budgetMs)
        if (System.currentTimeMillis() - started > budgetMs) {
            return Attempt2(Outcome.TIMEOUT)
        }
        return isOnline
    }

    @Suppress("DEPRECATION")
    private fun runAttempt(
        cfg: AppConfig,
        net: NetInfo,
        target: LoginTarget,
        budgetMs: Long,
    ): Attempt2 {
        val wifiNet = net.wifiNetwork
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val gw = target.gateway
        val acc = target.account
        val label = maskUser(if (acc.userType.isNotBlank()) "${acc.username}@${acc.userType}" else acc.username)

        // VPN 三层降级（v0.2.2 的修复，保留）
        var mode = if (net.vpnActive && wifiNet != null) {
            if (bindBlocked) BindMode.PROCESS else BindMode.SOCKET
        } else {
            BindMode.NONE
        }
        var processBound = false

        fun bind(): Boolean {
            if (mode == BindMode.PROCESS && wifiNet != null && !processBound) {
                processBound = runCatching { cm?.bindProcessToNetwork(wifiNet) == true }
                    .getOrDefault(false)
            }
            return processBound
        }

        try {
            var attempt = oneCall(cfg, gw, acc, net, mode, budgetMs, label)
            if (!attempt.answered && mode == BindMode.SOCKET) {
                bindBlocked = true
                mode = BindMode.PROCESS
                bind()
                attempt = oneCall(cfg, gw, acc, net, mode, budgetMs, label)
            }
            if (!attempt.answered && mode == BindMode.PROCESS) {
                mode = BindMode.NONE
                attempt = oneCall(cfg, gw, acc, net, mode, budgetMs, label)
            }
            if (attempt.answered) vpnStrategy = mode
            return attempt
        } finally {
            if (processBound) {
                runCatching { cm?.bindProcessToNetwork(null) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun oneCall(
        cfg: AppConfig,
        gw: Gateway,
        acc: Account,
        net: NetInfo,
        mode: BindMode,
        budgetMs: Long,
        label: String,
    ): Attempt2 {
        val wifiNet = net.wifiNetwork
        val binder: org.xjtulogin.core.ConnectionFactory? =
            if (mode == BindMode.SOCKET && wifiNet != null) {
                org.xjtulogin.core.ConnectionFactory { url ->
                    (wifiNet.openConnection(url) as? java.net.HttpURLConnection)
                        ?: throw java.io.IOException("Binding socket to network failed: unsupported connection")
                }
            } else null

        val timeout = minOf(cfg.timeoutMs.toLong(), budgetMs).toInt().coerceIn(1000, 60_000)
        val client = SrunClient(baseUrlOf(gw.domain), timeout, connectionFactory = binder)
        val localIp = net.ipv4 ?: ""
        return try {
            val (online, gwIp) = client.loginStatus()
            // 认证 IP 必须用「网关自己报的 client_ip」—— 那才是网关认的地址。
            // 本机网卡 IP 在部分区域（实测：食堂）与它不一致 ⇒ 拿本机 IP 去登录会被判 login_error。
            val authIp = gwIp?.takeIf { it.isNotBlank() } ?: localIp
            if (online) {
                Attempt2(
                    Outcome.ONLINE, gwIp ?: authIp,
                    "$label@${hostOf(gw.domain)} 已在线",
                    answered = true, alreadyOnline = true
                )
            } else {
                val form = LoginForm(
                    domain = gw.domain,
                    username = acc.username,
                    userType = acc.userType,
                    password = acc.password,
                )
                val meta = LoginMeta(
                    acid = gw.acid, n = gw.n, type = gw.type,
                    os = cfg.metaOs, name = cfg.metaName,
                )
                when (val r = client.doLogin(form, meta, authIp)) {
                    is SrunResult.Ok -> Attempt2(
                        Outcome.ONLINE, r.ip,
                        "$label@${hostOf(gw.domain)} 登录成功 IP=${r.ip ?: authIp}" +
                            if (authIp != localIp) "（认证IP≠本机IP）" else "",
                        answered = true
                    )
                    is SrunResult.Rejected -> {
                        if (r.error.contains("online", ignoreCase = true)) {
                            // 网关认为该 IP/账号已在线，等价于成功
                            Attempt2(
                                Outcome.ONLINE, authIp,
                                "$label@${hostOf(gw.domain)} 网关提示已在线：${r.error}",
                                answered = true, alreadyOnline = true
                            )
                        } else {
                            Attempt2(
                                Outcome.FAILED, null,
                                "$label@${hostOf(gw.domain)} 拒绝：${r.error}${loginErrorHint(r.error)}",
                                answered = true
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Attempt2(Outcome.FAILED, null, "$label@${hostOf(gw.domain)} 失败：${friendlyError(t)}")
        }
    }

    /** 网关错误码补一句人话（login_error 基本都是后缀/密码问题） */
    private fun loginErrorHint(error: String): String = when {
        error.contains("login_error", ignoreCase = true) ->
            "（查账号密码，及运营商后缀：西交校园网填 stu）"
        error.contains("E2531") || error.contains("欠费") -> "（账号欠费）"
        error.contains("E2553") -> "（账号已在别处在线）"
        else -> ""
    }

    // ---------------------------------------------------------------- 状态卡 / 等待

    private fun publishState(ssid: String?, ip: String?, online: Boolean) {
        val net = WifiKit.snapshot(this)
        val detail = buildString {
            append("SSID=").append(ssid?.takeIf { it.isNotBlank() } ?: net.ssid ?: "未知")
            append(" IP=").append(ip ?: net.ipv4 ?: "-")
            vpnStrategy?.let {
                append(" · VPN:")
                append(
                    when (it) {
                        BindMode.SOCKET -> "已绕过(绑网卡)"
                        BindMode.PROCESS -> "已绕过(进程绑WiFi)"
                        BindMode.NONE -> "走默认路由"
                    }
                )
            }
        }
        StatusStore.setStatus(
            this,
            if (online) "已在线" else "未登录",
            detail,
            if (online) StatusLevel.ONLINE else StatusLevel.OFFLINE,
            ssid ?: net.ssid,
            ip ?: net.ipv4,
        )
    }

    /** 原脚本的 `sleep t`，但要能被「网络变化 / 手动检测 / 停止」提前叫醒 */
    private fun sleepInterruptible(sec: Int): Boolean {
        if (!running) return false
        StatusStore.beat(this)
        val deadline = System.currentTimeMillis() + sec * 1000L
        synchronized(gate) {
            while (running && System.currentTimeMillis() < deadline) {
                if (wakeRequested && System.currentTimeMillis() - lastWakeAt > 500L) break
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    gate.wait(minOf(left, 1000L))
                } catch (_: InterruptedException) {
                    return false
                }
            }
            wakeRequested = false
        }
        return running
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    /** 唤醒等待（网络变化/手动检测）：让 5s 的 sleep 立刻结束，重新走一遍状态机 */
    private fun wake(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWakeAt < 3_000L) return
        lastWakeAt = now
        synchronized(gate) {
            wakeRequested = true
            gate.notifyAll()
        }
    }

    private fun registerNetworkWatch() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wake()
            override fun onLost(network: Network) = wake()
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 工具

    /** SSID 宽松比较：忽略大小写，- 与空格视作 _（xjtu-stu / XJTU_STU 都能命中） */
    private fun ssidKey(s: String?): String? =
        s?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')

    /** 去掉协议前缀与结尾斜杠，只留 host（用于比对/记忆） */
    private fun hostOf(domain: String): String =
        domain.removePrefix("http://").removePrefix("https://").trimEnd('/')

    private fun baseUrlOf(domain: String): String {
        val base = if (domain.startsWith("http://") || domain.startsWith("https://")) domain
        else "http://$domain"
        return if (base.endsWith("/")) base else "$base/"
    }

    /** 学号脱敏：日志/通知里不出现完整学号（同学常把日志贴群里求助） */
    private fun maskUser(name: String): String {
        val base = name.substringBefore('@')
        val suffix = name.substringAfter('@', "")
        val masked = if (base.length <= 4) "****" else base.take(4) + "****" + base.takeLast(2)
        return if (suffix.isEmpty()) masked else "$masked@$suffix"
    }

    /** 把底层异常翻译成用户能看懂的一句话（尤其 cleartext 这种坑） */
    private fun friendlyError(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return when {
            raw.contains("CLEARTEXT", ignoreCase = true) ||
                raw.contains("not permitted by network security policy", ignoreCase = true) ->
                "系统拦截明文 HTTP（network security config 缺失）"
            raw.contains("Binding socket to network", ignoreCase = true) ->
                "系统拒绝绑网卡（EPERM），已改走默认路由"
            raw.contains("not an object") || raw.contains("<script", ignoreCase = true) ->
                "该网关返回网页而非接口数据（可能不是当前网络的有效 AC）"
            t is java.net.SocketTimeoutException -> "连接网关超时"
            t is java.net.ConnectException -> "无法连接网关（不在校园网？）"
            t is java.net.UnknownHostException -> "无法解析网关地址"
            else -> raw.take(120)
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun startForegroundCompat(content: String) {
        val n = baseBuilder()
            .setContentTitle(getString(R.string.app_title))
            .setContentText(content)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyStatus() {
        val s = StatusStore.get(this)
        val n = baseBuilder()
            .setContentTitle("${getString(R.string.app_title)}：${s.summary}")
            .setContentText(s.detail.ifBlank { getString(R.string.notif_channel_desc) })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    s.logTail(8).ifBlank { s.detail.ifBlank { getString(R.string.notif_channel_desc) } }
                )
            )
            .setOngoing(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
        } catch (_: Throwable) {
        }
    }

    /** 原脚本 `termux-notification --title 电池消耗警告 --priority high` 的等价物 */
    private fun notifyBatteryWarning(count: Int, where: String) {
        if (!PowerKit.notificationsEnabled(this)) return
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("电池消耗警告")
            .setContentText("高耗电：正在刷新dumpsys (连续${count}次) · $where")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 3, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            NotificationManagerCompat.from(this).notify(BATTERY_NOTIF_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setColor(ContextCompat.getColor(this, R.color.md_primary))
            .setContentIntent(activityIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_action_check), serviceIntent(ACTION_CHECK_NOW, 11))
            .addAction(0, getString(R.string.notif_action_stop), serviceIntent(ACTION_STOP, 12))

    private fun activityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, LoginService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun shutdown() {
        running = false
        wake(force = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

}
