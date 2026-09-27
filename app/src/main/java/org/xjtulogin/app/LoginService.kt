package org.xjtulogin.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.xjtulogin.core.LoginForm
import org.xjtulogin.core.LoginMeta
import org.xjtulogin.core.SrunClient
import org.xjtulogin.core.SrunResult

class LoginService : Service() {

    companion object {
        const val CHANNEL_ID = "login_status"
        const val NOTIF_ID = 1
    }

    private var thread: Thread? = null
    @Volatile private var running = false
    private val sb = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "保活状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("正在启动保活…"))
        if (!running) {
            running = true
            thread = Thread { loop() }.apply {
                name = "login-loop"
                start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        while (running) {
            val nextWait = try {
                runCycle()
            } catch (e: Throwable) {
                if (!running) 0
                else 30_000L // 异常退避
            }
            if (!running) break
            try {
                Thread.sleep(nextWait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** 单轮检测；返回下一轮等待毫秒；fail 快速重试，成功走轮询间隔 */
    private fun runCycle(): Long {
        val cfg = AppStore.load(this)
        if (!cfg.serviceEnabled) {
            LoginStatusHolder.update("已停止", "设置中已关闭保活", "service disabled")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            running = false
            return 0
        }

        sb.setLength(0)
        val ssid = WifiKit.currentSsid(this)

        // 宿舍网：跳过（不占校园网账号）
        if (!cfg.roomSsids.isEmpty() && ssid != null && ssid in cfg.roomSsids) {
            val msg = "宿舍网模式（$ssid），跳过登录"
            LoginStatusHolder.update("宿舍网，已跳过", "SSID=$ssid", msg)
            notify("宿舍网模式", msg)
            return cfg.pollIntervalSec * 1000L
        }

        // 目标 SSID 校验
        if (cfg.targetSsids.isNotEmpty() && (ssid == null || ssid !in cfg.targetSsids)) {
            val msg = "当前 WiFi：${ssid ?: "无/无法读取"}，不在目标列表 ${cfg.targetSsids}"
            LoginStatusHolder.update("不在校园网", msg, msg)
            notify("跳过本轮", msg)
            return cfg.pollIntervalSec * 1000L
        }

        val ip = WifiKit.currentIpv4(this)
        sb.append("SSID=$ssid IP=$ip\n")

        val accounts = cfg.accounts.filter { it.enabled && it.username.isNotBlank() }
        val gateways = cfg.gateways.filter { it.enabled && it.domain.isNotBlank() }
        if (accounts.isEmpty() || gateways.isEmpty()) {
            val msg = "未配置可用的账号或网关，请在设置中添加"
            LoginStatusHolder.update("配置缺失", msg, msg)
            notify("配置缺失", msg)
            return cfg.pollIntervalSec * 1000L
        }

        var loggedIn = false
        var tried = 0
        for (acc in accounts) {
            if (!running) break
            for (gw in gateways) {
                if (!running) break
                tried++
                val client = SrunClient("http://${gw.domain}/", cfg.timeoutMs)
                sb.append("→ ${acc.username} @ ${gw.domain} ")
                try {
                    val (online, _) = client.loginStatus()
                    if (online) {
                        sb.append("已在线 ✓\n")
                        loggedIn = true
                        break
                    }
                    sb.append("未在线，尝试登录… ")
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
                    val r = client.doLogin(form, meta, ip ?: "")
                    when (r) {
                        is SrunResult.Ok -> {
                            sb.append("登录成功 ✓ (${r.ip ?: ip})\n")
                            loggedIn = true
                            break
                        }
                        is SrunResult.Rejected -> sb.append("拒绝: ${r.error}\n")
                    }
                } catch (e: Exception) {
                    sb.append("错误: ${e.message?.take(80)}\n")
                }
            }
            if (loggedIn) break
        }

        val logText = sb.toString()
        val summary = if (loggedIn) "已在线" else "未登录（$tried 次尝试失败）"
        LoginStatusHolder.update(summary, "SSID=$ssid IP=$ip · $tried 次尝试", logText)
        notify(summary, logText.trim().lines().lastOrNull() ?: "")
        return cfg.pollIntervalSec * 1000L
    }

    private fun baseIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(content: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("校园网保活：${LoginStatusHolder.summary}")
            .setContentText(content)
            .setContentIntent(baseIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notify(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("校园网保活：$title")
            .setContentText(text)
            .setContentIntent(baseIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}