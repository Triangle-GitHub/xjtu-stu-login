// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.xjtulogin.app.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import org.xjtulogin.app.databinding.ItemNavBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var logExpanded = false
    private var lastHeadline: String? = null
    private var busyUntil = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    /** Shizuku 授权结果：刷新界面（授权后「WiFi 自动修复」那一行会变） */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (ok) R.string.shizuku_granted else R.string.shizuku_denied,
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnStart.setOnClickListener { startKeepAlive() }
        b.btnStop.setOnClickListener {
            LoginService.stop(this)
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
            refresh()
        }
        b.btnRefresh.setOnClickListener {
            markBusy()
            LoginService.checkNow(this)
            Toast.makeText(this, R.string.toast_checking, Toast.LENGTH_SHORT).show()
        }
        b.btnOneShot.setOnClickListener { oneShotLogin() }

        b.itemSettings.root.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.itemSettings.navIcon.setImageResource(R.drawable.ic_settings)
        b.itemSettings.navTitle.setText(R.string.nav_settings_title)
        b.itemSettings.navSubtitle.setText(R.string.nav_settings_subtitle)

        b.itemWifi.root.setOnClickListener { onWifiFixClicked() }
        b.itemWifi.navIcon.setImageResource(R.drawable.ic_wifi)

        b.itemBattery.root.setOnClickListener { PowerKit.requestIgnoreBatteryOptimizations(this) }
        b.itemBattery.navIcon.setImageResource(R.drawable.ic_battery)
        b.itemBattery.navTitle.setText(R.string.nav_battery_title)

        b.itemNotification.root.setOnClickListener { PowerKit.openNotificationSettings(this) }
        b.itemNotification.navIcon.setImageResource(R.drawable.ic_notifications)
        b.itemNotification.navTitle.setText(R.string.nav_notification_title)

        // 首启三步引导
        b.stepAccount.root.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.stepBattery.root.setOnClickListener { PowerKit.requestIgnoreBatteryOptimizations(this) }
        b.stepStart.root.setOnClickListener { startKeepAlive() }
        b.btnOnboardingClose.setOnClickListener {
            AppStore.save(this, AppStore.load(this).copy(onboardingDismissed = true))
            refresh()
        }

        b.btnToggleLog.setOnClickListener {
            logExpanded = !logExpanded
            applyLogState()
        }
        b.btnCopyLog.setOnClickListener { copyLog() }

        b.footer.text = getString(R.string.footer_version, versionName())

        ShizukuShell.addPermissionListener(shizukuPermissionListener)
        requestPermissions()
        autoRecover()
        applyLogState()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed(ticker, 1500L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        ShizukuShell.removePermissionListener(shizukuPermissionListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 保活开关

    private fun startKeepAlive() {
        // 先落盘开关，再拉服务：服务重建/被杀后仍知道自己该跑
        AppStore.save(this, AppStore.load(this).copy(serviceEnabled = true))
        markBusy()
        LoginService.start(this)
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refresh() }, 400L)
    }

    /**
     * 单次登录：复刻原脚本「未连接」那一遍（必要时连 WiFi → 依次试 配置18/21 → 成败即止）。
     * 保活已经在跑时，改插一次即时检测（不然两个状态机会打架）。
     */
    private fun oneShotLogin() {
        markBusy(6000L)
        val running = AppStore.load(this).serviceEnabled
        if (running) {
            LoginService.checkNow(this)
            Toast.makeText(this, R.string.toast_one_shot_while_running, Toast.LENGTH_SHORT).show()
        } else {
            LoginService.oneShot(this)
            Toast.makeText(this, R.string.toast_one_shot, Toast.LENGTH_SHORT).show()
        }
        logExpanded = true
        applyLogState()
        handler.postDelayed({ refresh() }, 500L)
    }

    /** 进程被系统回收后回到前台：开关是开的但服务没心跳 ⇒ 立刻自愈 */
    private fun autoRecover() {
        val cfg = AppStore.load(this)
        if (cfg.serviceEnabled && !StatusStore.isAlive(this, cfg.pollIntervalSec)) {
            LoginService.start(this)
            markBusy()
        }
    }

    private fun markBusy(ms: Long = 2500L) {
        busyUntil = System.currentTimeMillis() + ms
        refresh()
    }

    // ------------------------------------------------------------ WiFi 自修复 / Shizuku

    /**
     * 一行说明「WiFi 自动修复」现在处于哪一级：
     *  没开开关 → 去设置打开；Shizuku 没跑 → 提示；没授权 → 点这里授权；已授权 → 可以自动开 WiFi。
     */
    private fun onWifiFixClicked() {
        val cfg = AppStore.load(this)
        when {
            !cfg.useShizuku -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                Toast.makeText(this, R.string.shizuku_enable_in_settings, Toast.LENGTH_SHORT).show()
            }
            !ShizukuShell.binderAlive() -> {
                // Shizuku 没运行：能装就拉起来，不能就给一句人话
                val opened = try {
                    startActivity(packageManager.getLaunchIntentForPackage(SHIZUKU_PKG))
                    true
                } catch (_: Throwable) {
                    false
                }
                if (!opened) {
                    Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL))
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
            !ShizukuShell.hasPermission() -> ShizukuShell.requestPermission()
            else -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                Toast.makeText(this, R.string.shizuku_granted, Toast.LENGTH_SHORT).show()
            }
        }
        handler.postDelayed({ refresh() }, 600L)
    }

    private fun shizukuSubtitle(cfg: AppConfig): String = when {
        !cfg.useShizuku -> getString(R.string.wifi_fix_off)
        !ShizukuShell.binderAlive() -> getString(R.string.wifi_fix_no_binder)
        !ShizukuShell.hasPermission() -> getString(R.string.wifi_fix_no_permission)
        else -> getString(R.string.wifi_fix_ready, ShizukuShell.describe())
    }

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        val cfg = AppStore.load(this)
        val status = StatusStore.get(this)
        val alive = StatusStore.isAlive(this, cfg.pollIntervalSec)
        val running = cfg.serviceEnabled

        b.statusMain.text = if (running) status.summary else getString(R.string.status_idle)
        b.statusDetail.text = when {
            !running -> getString(R.string.status_stopped_hint)
            !alive -> getString(R.string.status_dead_hint)
            else -> status.detail
        }
        b.statusMeta.text = if (status.updatedAt > 0L) {
            getString(R.string.status_last_updated, timeFmt.format(Date(status.updatedAt)))
        } else {
            getString(R.string.status_unknown_meta)
        }

        // 状态徽章：颜色与图标随状态变；标题变化时做一次轻微弹入
        b.statusBadge.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, badgeColorRes(running, alive, status.level))
        )
        b.statusBadgeIcon.setImageResource(
            when {
                !running -> R.drawable.ic_wifi
                status.level == StatusLevel.ONLINE -> R.drawable.ic_check_circle
                status.level == StatusLevel.OFFLINE -> R.drawable.ic_error
                status.level == StatusLevel.WARN -> R.drawable.ic_info
                else -> R.drawable.ic_wifi
            }
        )
        val headline = b.statusMain.text.toString()
        if (headline != lastHeadline) {
            lastHeadline = headline
            b.statusBadge.animate().cancel()
            b.statusBadge.scaleX = 0.86f
            b.statusBadge.scaleY = 0.86f
            b.statusBadge.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
        }

        // 网络胶囊：用结构化字段，不再解析 detail 文本
        val ssid = status.ssid ?: getString(R.string.network_unknown)
        val ip = status.ip
        b.statusNetwork.text = if (ip.isNullOrBlank()) ssid else "$ssid · $ip"

        b.btnStart.isEnabled = !running
        b.btnStop.isEnabled = running
        b.btnOneShot.isEnabled = true
        b.statusProgress.visibility =
            if (System.currentTimeMillis() < busyUntil) View.VISIBLE else View.GONE

        // WiFi 自动修复能力（Shizuku）
        b.itemWifi.navTitle.setText(R.string.nav_wifi_fix_title)
        b.itemWifi.navSubtitle.text = shizukuSubtitle(cfg)
        b.itemWifi.navIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (cfg.useShizuku && ShizukuShell.hasPermission()) R.color.md_success else R.color.md_primary
            )
        )

        val batteryOk = PowerKit.ignoringBatteryOptimizations(this)
        b.itemBattery.navSubtitle.setText(
            if (batteryOk) R.string.battery_ok else R.string.battery_need
        )
        b.itemBattery.navIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (batteryOk) R.color.md_primary else R.color.md_error)
        )

        val notifOk = PowerKit.notificationsEnabled(this)
        b.itemNotification.root.visibility = if (notifOk) View.GONE else View.VISIBLE
        b.dividerNotification.visibility = if (notifOk) View.GONE else View.VISIBLE
        if (!notifOk) b.itemNotification.navSubtitle.setText(R.string.notif_need)

        // 首启引导：三步全绿或手动关掉后隐藏
        val hasAccount = cfg.accounts.any { it.enabled && it.username.isNotBlank() }
        val keepAliveOn = running && alive
        markStep(b.stepAccount, R.drawable.ic_person, R.string.step_account_title,
            R.string.step_account_desc, hasAccount)
        markStep(b.stepBattery, R.drawable.ic_battery, R.string.step_battery_title,
            R.string.step_battery_desc, batteryOk)
        markStep(b.stepStart, R.drawable.ic_play, R.string.step_start_title,
            R.string.step_start_desc, keepAliveOn)
        val onboardingDone = hasAccount && batteryOk && keepAliveOn
        b.onboardingCard.visibility =
            if (onboardingDone || cfg.onboardingDismissed) View.GONE else View.VISIBLE

        applyLogState()
    }

    /** 引导步骤：未完成=原图标+说明；完成=绿勾+「已完成」 */
    private fun markStep(
        step: ItemNavBinding,
        iconRes: Int,
        titleRes: Int,
        descRes: Int,
        done: Boolean,
    ) {
        step.navIcon.setImageResource(if (done) R.drawable.ic_check_circle else iconRes)
        step.navIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (done) R.color.md_success else R.color.md_primary)
        )
        step.navTitle.setText(titleRes)
        step.navSubtitle.text = getString(descRes) + if (done) getString(R.string.step_done_suffix) else ""
    }

    /** 徽章底色：在线绿 / 失败红 / 跳过黄 / 未启动灰 */
    @ColorRes
    private fun badgeColorRes(running: Boolean, alive: Boolean, level: StatusLevel): Int = when {
        !running -> R.color.md_outline
        !alive -> R.color.md_error
        level == StatusLevel.ONLINE -> R.color.md_success
        level == StatusLevel.OFFLINE -> R.color.md_error
        level == StatusLevel.WARN -> R.color.md_warning
        else -> R.color.md_outline
    }

    /**
     * 日志：原脚本是终端滚动（最新在最下），所以
     * 收起时只显示**最后几行**（不是最前面几行 —— 那会永远停在启动那几行），展开时给全量。
     */
    private fun applyLogState() {
        val status = StatusStore.get(this)
        val lines = if (logExpanded) status.lines else status.lines.takeLast(COLLAPSED_LOG_LINES)
        b.statusLog.text =
            if (lines.isEmpty()) getString(R.string.log_empty) else LogKit.render(this, lines)
        b.statusLog.maxLines = if (logExpanded) 400 else COLLAPSED_LOG_LINES
        b.statusLog.ellipsize = if (logExpanded) null else TextUtils.TruncateAt.END
        b.btnToggleLog.setText(if (logExpanded) R.string.log_collapse else R.string.log_expand)

        val hasLog = status.lines.isNotEmpty()
        b.btnToggleLog.isEnabled = hasLog
        b.btnCopyLog.isEnabled = hasLog
    }

    private fun copyLog() {
        val status = StatusStore.get(this)
        val text = buildString {
            append("summary: ").append(status.summary).append('\n')
            append("detail: ").append(status.detail).append('\n')
            append(status.log)
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("xjtu-login-log", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 权限

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        // SSID 需要定位权限；13+ 还要 NEARBY_WIFI_DEVICES（neverForLocation）
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "2.1.0"
    } catch (_: Throwable) {
        "2.1.0"
    }

    private companion object {
        const val COLLAPSED_LOG_LINES = 10
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"
    }
}
