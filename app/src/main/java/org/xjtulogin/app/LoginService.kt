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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.xjtulogin.core.LoginForm
import org.xjtulogin.core.LoginMeta
import org.xjtulogin.core.SrunClient
import org.xjtulogin.core.SrunResult

/**
 * 保活前台服务。
 *
 * 保活链条（缺一不可）：
 *  1. 明文 HTTP 放行（res/xml/network_security_config.xml）—— 否则每轮都是
 *     "CLEARTEXT communication ... not permitted"，登录永远失败；
 *  2. 前台服务 + START_STICKY + 常驻通知（被系统回收后自动重建）；
 *  3. 网络变化立即重检（ConnectivityManager 回调唤醒轮询，不等下一个间隔）；
 *  4. 心跳落盘（进程真死了，回前台能立刻发现并自愈）；
 *  5. 电池优化白名单（Doze/省电策略下不被掐）。
 */
class LoginService : Service() {

    companion object {
        const val CHANNEL_ID = "keepalive"
        const val NOTIF_ID = 1001
        const val ACTION_START = "org.xjtulogin.app.action.START"
        const val ACTION_STOP = "org.xjtulogin.app.action.STOP"
        const val ACTION_CHECK_NOW = "org.xjtulogin.app.action.CHECK_NOW"

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
            StatusStore.update(ctx, "已停止", "手动停止保活", "", StatusLevel.IDLE)
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
    }

    private val gate = Object()
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var oneShot = false
    @Volatile private var wakeRequested = false
    @Volatile private var lastWakeAt = 0L

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
                StatusStore.update(this, "已停止", "已从通知停止保活", "", StatusLevel.IDLE)
                shutdown()
                return START_NOT_STICKY
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
            oneShot = !cfg.serviceEnabled
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

    // ---------------------------------------------------------------- 轮询循环

    private fun loop() {
        while (running) {
            var waitMs = 30_000L
            try {
                waitMs = withWakeLock { runCycle() }
            } catch (t: Throwable) {
                if (running) {
                    StatusStore.update(
                        this, "检测异常", t.message ?: t.javaClass.simpleName, "", StatusLevel.OFFLINE
                    )
                    waitMs = 30_000L
                }
            }
            if (!running || oneShot) break
            synchronized(gate) {
                // 期间有网络变化/手动检测 ⇒ 立刻再来一轮
                if (running && !wakeRequested) {
                    try {
                        gate.wait(waitMs.coerceAtLeast(2_000L))
                    } catch (_: InterruptedException) {
                    }
                }
                wakeRequested = false
            }
        }
        if (oneShot) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 唤醒轮询（3000ms 内去抖，避免网络回调风暴导致连跑） */
    private fun wake(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWakeAt < 3_000L) return
        lastWakeAt = now
        synchronized(gate) {
            wakeRequested = true
            gate.notifyAll()
        }
    }

    /** CPU 在 Doze 下会休眠，整轮检测期间短暂持锁，保证请求真的发出去 */
    private inline fun <T> withWakeLock(block: () -> T): T {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val lock = try {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xjtulogin:cycle")
        } catch (_: Throwable) {
            null
        }
        try {
            try {
                lock?.acquire(60_000L)
            } catch (_: Throwable) {
            }
            return block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Throwable) {
            }
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

    // ---------------------------------------------------------------- 单轮检测

    /** 单轮检测；返回下一轮等待毫秒 */
    private fun runCycle(): Long {
        val cfg = AppStore.load(this)
        val pollMs = cfg.pollIntervalSec.coerceIn(5, 86400) * 1000L
        val net = WifiKit.snapshot(this)

        // 1) 宿舍网：跳过（不占用校园网账号）
        if (cfg.roomSsids.isNotEmpty() && net.ssid != null && net.ssid in cfg.roomSsids) {
            val text = "宿舍网 ${net.ssid}，跳过登录"
            publish("宿舍网，已跳过", text, "SSID=${net.ssid} IP=${net.ipv4 ?: "-"}\n已跳过本轮", StatusLevel.IDLE)
            return pollMs
        }

        // 2) 网络形态校验
        if (!net.wifi) {
            val text = if (net.connected) "当前不是 WiFi（可能在用移动数据）" else "当前没有可用网络"
            publish("未连接 WiFi", text, text, StatusLevel.WARN)
            return pollMs
        }
        if (cfg.targetSsids.isNotEmpty() && net.ssid != null && net.ssid !in cfg.targetSsids) {
            val text = "当前 WiFi「${net.ssid}」不在目标列表 ${cfg.targetSsids}"
            publish("不在校园网", text, text, StatusLevel.WARN)
            return pollMs
        }

        val accounts = cfg.accounts.filter { it.enabled && it.username.isNotBlank() }
        val gateways = cfg.gateways.filter { it.enabled && it.domain.isNotBlank() }
        if (accounts.isEmpty() || gateways.isEmpty()) {
            val text = "请在设置里添加并勾选账号与网关"
            publish("缺少配置", text, text, StatusLevel.WARN)
            return pollMs
        }

        val sb = StringBuilder()
        sb.append("SSID=${net.ssid ?: "(读不到)"} IP=${net.ipv4 ?: "(未知)"}\n")
        if (net.ssid == null) {
            sb.append("! 读不到 SSID（缺定位权限？）—— 已按未知网络继续尝试\n")
        }

        var online = false
        var attempts = 0
        var lastError: String? = null
        var lastLevel = StatusLevel.OFFLINE
        var onlineIp: String? = null

        loop@ for (acc in accounts) {
            if (!running) break
            for (gw in gateways) {
                if (!running) break
                attempts++
                sb.append("→ ${acc.username} @ ${gw.domain} ")
                val client = SrunClient(baseUrlOf(gw.domain), cfg.timeoutMs)
                try {
                    val (isOnline, ip) = client.loginStatus()
                    if (isOnline) {
                        sb.append("已在线 ✓\n")
                        online = true
                        onlineIp = ip
                        break@loop
                    }
                    sb.append("未在线，登录… ")
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
                    when (val r = client.doLogin(form, meta, net.ipv4 ?: "")) {
                        is SrunResult.Ok -> {
                            sb.append("登录成功 ✓ ${r.ip ?: ""}\n")
                            online = true
                            onlineIp = r.ip
                            break@loop
                        }
                        is SrunResult.Rejected -> {
                            if (r.error.contains("online", ignoreCase = true)) {
                                // 网关认为该 IP/账号已在线，等价于成功
                                sb.append("网关提示已在线 ✓ (${r.error})\n")
                                online = true
                                break@loop
                            }
                            sb.append("拒绝: ${r.error}\n")
                            lastError = r.error
                            lastLevel = StatusLevel.OFFLINE
                        }
                    }
                } catch (t: Throwable) {
                    val msg = friendlyError(t)
                    sb.append("失败: $msg\n")
                    lastError = msg
                    lastLevel = if (msg.contains("拦截") || msg.contains("权限")) StatusLevel.WARN
                    else StatusLevel.OFFLINE
                }
            }
        }

        val ipText = onlineIp ?: net.ipv4
        val summary = if (online) "已在线" else "未登录"
        val detail = buildString {
            append("SSID=").append(net.ssid ?: "未知")
            append(" IP=").append(ipText ?: "-")
            append(" · 尝试 ").append(attempts).append(" 次")
            if (!online && lastError != null) append(" · ").append(lastError)
        }
        publish(summary, detail, sb.toString(), if (online) StatusLevel.ONLINE else lastLevel)
        return pollMs
    }

    private fun baseUrlOf(domain: String): String {
        val base = if (domain.startsWith("http://") || domain.startsWith("https://")) domain
        else "http://$domain"
        return if (base.endsWith("/")) base else "$base/"
    }

    /** 把底层异常翻译成用户能看懂的一句话（尤其 cleartext 这种坑） */
    private fun friendlyError(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return when {
            raw.contains("CLEARTEXT", ignoreCase = true) ||
                raw.contains("not permitted by network security policy", ignoreCase = true) ->
                "系统拦截明文 HTTP（network security config 缺失）"
            t is java.net.SocketTimeoutException -> "连接网关超时"
            t is java.net.ConnectException -> "无法连接网关（不在校园网？）"
            t is java.net.UnknownHostException -> "无法解析网关地址"
            else -> raw.take(120)
        }
    }

    private fun publish(summary: String, detail: String, log: String, level: StatusLevel) {
        StatusStore.update(this, summary, detail, log, level)
        notifyStatus()
    }

    // ---------------------------------------------------------------- 通知

    private fun startForegroundCompat(content: String) {
        val n = baseBuilder()
            .setContentTitle(getString(R.string.app_name))
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
            .setContentTitle("${getString(R.string.app_name)}：${s.summary}")
            .setContentText(s.detail.ifBlank { getString(R.string.notif_channel_desc) })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    s.log.ifBlank { s.detail.ifBlank { getString(R.string.notif_channel_desc) } }
                )
            )
            .setOngoing(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
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
