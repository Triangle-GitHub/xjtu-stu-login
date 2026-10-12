package org.xjtulogin.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import org.xjtulogin.app.databinding.ItemNavBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var logExpanded = false
    private var busyUntil = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

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

        b.itemSettings.root.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.itemSettings.navIcon.setImageResource(R.drawable.ic_settings)
        b.itemSettings.navTitle.setText(R.string.nav_settings_title)
        b.itemSettings.navSubtitle.setText(R.string.nav_settings_subtitle)

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

    // ------------------------------------------------------------ 保活开关

    private fun startKeepAlive() {
        // 先落盘开关，再拉服务：服务重建/被杀后仍知道自己该跑
        AppStore.save(this, AppStore.load(this).copy(serviceEnabled = true))
        markBusy()
        LoginService.start(this)
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refresh() }, 400L)
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

        b.statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColor(running, alive, status.level))
        )

        b.btnStart.isEnabled = !running
        b.btnStop.isEnabled = running
        b.statusProgress.visibility =
            if (System.currentTimeMillis() < busyUntil) View.VISIBLE else View.GONE

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

    @ColorRes
    private fun dotColor(running: Boolean, alive: Boolean, level: StatusLevel): Int = when {
        !running -> R.color.md_outline
        !alive -> R.color.md_error
        level == StatusLevel.ONLINE -> R.color.md_success
        level == StatusLevel.OFFLINE -> R.color.md_error
        level == StatusLevel.WARN -> R.color.md_warning
        else -> R.color.md_outline
    }

    private fun applyLogState() {
        val status = StatusStore.get(this)
        b.statusLog.text = status.log.ifBlank { getString(R.string.log_empty) }
        b.statusLog.maxLines = if (logExpanded) 200 else 8
        b.statusLog.ellipsize = if (logExpanded) null else TextUtils.TruncateAt.END
        b.btnToggleLog.setText(if (logExpanded) R.string.log_collapse else R.string.log_expand)

        val hasLog = status.log.isNotBlank()
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.2.0"
    } catch (_: Throwable) {
        "0.2.0"
    }
}
