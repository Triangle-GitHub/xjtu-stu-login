// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.xjtulogin.app.databinding.AccountRowBinding
import org.xjtulogin.app.databinding.ActivitySettingsBinding
import org.xjtulogin.app.databinding.GatewayRowBinding
import org.xjtulogin.app.databinding.RoomRowBinding
import rikka.shizuku.Shizuku

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var dirty = false

    /** 动态行的绑定对象：保存时直接读，不再按 id 从视图树里找 */
    private val gatewayRows = mutableListOf<GatewayRowBinding>()
    private val accountRows = mutableListOf<AccountRowBinding>()
    private val roomRows = mutableListOf<RoomRowBinding>()
    private var advancedOpen = false

    private val dirtyWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            markDirty()
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (ok) R.string.shizuku_granted else R.string.shizuku_denied,
                Toast.LENGTH_SHORT
            ).show()
            refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        ShizukuShell.addPermissionListener(shizukuPermissionListener)
        setupHeaders()
        bindConfig()
        attachListeners()
        refreshShizuku()
    }

    override fun onDestroy() {
        ShizukuShell.removePermissionListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun setupHeaders() {
        b.toolbar.setNavigationOnClickListener { confirmExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        b.headerGateway.headerIcon.setImageResource(R.drawable.ic_router)
        b.headerGateway.headerTitle.setText(R.string.card_gateway_title)
        b.headerGateway.headerCaption.setText(R.string.card_gateway_caption)

        b.headerAccount.headerIcon.setImageResource(R.drawable.ic_person)
        b.headerAccount.headerTitle.setText(R.string.card_account_title)
        b.headerAccount.headerCaption.setText(R.string.card_account_caption)

        b.headerParam.headerIcon.setImageResource(R.drawable.ic_tune)
        b.headerParam.headerTitle.setText(R.string.card_param_title)
        b.headerParam.headerCaption.setText(R.string.card_param_caption)

        b.headerNetwork.headerIcon.setImageResource(R.drawable.ic_wifi)
        b.headerNetwork.headerTitle.setText(R.string.card_network_title)
        b.headerNetwork.headerCaption.setText(R.string.card_network_caption)

        b.headerWifi.headerIcon.setImageResource(R.drawable.ic_wifi)
        b.headerWifi.headerTitle.setText(R.string.card_wifi_title)
        b.headerWifi.headerCaption.setText(R.string.card_wifi_caption)

        b.headerAdvanced.headerIcon.setImageResource(R.drawable.ic_code)
        b.headerAdvanced.headerTitle.setText(R.string.card_advanced_title)
        b.headerAdvanced.headerCaption.setText(R.string.card_advanced_caption)
        b.headerAdvanced.headerChevron.visibility = View.VISIBLE
        b.headerAdvanced.root.setOnClickListener { toggleAdvanced() }
    }

    private fun toggleAdvanced() {
        advancedOpen = !advancedOpen
        b.advancedContent.visibility = if (advancedOpen) View.VISIBLE else View.GONE
        b.headerAdvanced.headerChevron.animate()
            .rotation(if (advancedOpen) 90f else 0f)
            .setDuration(180L)
            .start()
    }

    private fun bindConfig() {
        val cfg = AppStore.load(this)
        cfg.gateways.forEach { addGatewayRow(it) }
        cfg.accounts.forEach { addAccountRow(it) }
        cfg.rooms.forEach { addRoomRow(it) }
        if (cfg.gateways.isEmpty()) addGatewayRow(Gateway())
        if (cfg.accounts.isEmpty()) addAccountRow(Account(userType = "stu"))

        b.etPollInterval.setText(cfg.pollIntervalSec.toString())
        b.etTimeout.setText(cfg.timeoutMs.toString())
        b.etRenewInterval.setText(cfg.renewIntervalSec.toString())
        b.etLoginTimeout.setText(cfg.loginTimeoutSec.toString())
        b.etScanInterval.setText(cfg.scanIntervalSec.toString())
        b.etTargetSsids.setText(cfg.targetSsids.joinToString(","))
        b.etMetaOs.setText(cfg.metaOs)
        b.etMetaName.setText(cfg.metaName)
        b.swAutoStart.isChecked = cfg.autoStart
        b.swWifiScan.isChecked = cfg.wifiScan
        b.swBatteryWarning.isChecked = cfg.batteryWarning
        b.swUseShizuku.isChecked = cfg.useShizuku
        b.swAutoEnableWifi.isChecked = cfg.autoEnableWifi

        // 监视放在填充之后：避免初始化就标脏
        listOf(
            b.etPollInterval, b.etTimeout, b.etRenewInterval, b.etLoginTimeout,
            b.etScanInterval, b.etTargetSsids, b.etMetaOs, b.etMetaName
        ).forEach { it.addTextChangedListener(dirtyWatcher) }
        listOf(
            b.swAutoStart, b.swWifiScan, b.swBatteryWarning,
            b.swUseShizuku, b.swAutoEnableWifi
        ).forEach { it.setOnCheckedChangeListener { _, _ -> markDirty() } }

        b.swAdvanced.isChecked = cfg.showAdvanced
        b.swAdvanced.setOnCheckedChangeListener { _, checked ->
            applyAdvanced(checked)
            markDirty()
        }
        applyAdvanced(cfg.showAdvanced)
    }

    /** 简化模式：默认只露账号 + WiFi 修复 + 开机自启；网关/SSID/时序参数收起 */
    private fun applyAdvanced(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        b.cardGateway.visibility = v
        b.cardNetwork.visibility = v
        b.cardAdvanced.visibility = v
        b.paramExtra.visibility = v
    }

    private fun attachListeners() {
        b.btnAddGw.setOnClickListener {
            addGatewayRow(Gateway())
            markDirty()
        }
        b.btnAddAccount.setOnClickListener {
            addAccountRow(Account(userType = "stu"))
            markDirty()
        }
        b.btnAddRoom.setOnClickListener {
            addRoomRow(RoomWifi())
            markDirty()
        }
        b.btnShizuku.setOnClickListener { onShizukuClicked() }
        b.swUseShizuku.setOnCheckedChangeListener { _, checked ->
            markDirty()
            if (checked) requestShizukuIfNeeded()
            refreshShizuku()
        }
        b.fabSave.setOnClickListener { save() }
    }

    // ------------------------------------------------------------- Shizuku

    /** 开关打开时顺手要一次授权（用户刚打开开关 = 意图明确） */
    private fun requestShizukuIfNeeded() {
        if (!ShizukuShell.binderAlive()) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }
        if (!ShizukuShell.hasPermission()) ShizukuShell.requestPermission()
    }

    private fun onShizukuClicked() {
        when {
            !ShizukuShell.binderAlive() -> {
                val launched = try {
                    startActivity(packageManager.getLaunchIntentForPackage(SHIZUKU_PKG))
                    true
                } catch (_: Throwable) {
                    false
                }
                if (!launched) {
                    Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
                    } catch (_: Throwable) {
                    }
                }
            }
            !ShizukuShell.hasPermission() -> ShizukuShell.requestPermission()
            else -> Toast.makeText(
                this,
                getString(R.string.shizuku_button, ShizukuShell.describe()),
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshShizuku()
    }

    private fun refreshShizuku() {
        b.btnShizuku.text = getString(R.string.shizuku_button, ShizukuShell.describe())
    }

    // ------------------------------------------------------------- 动态行

    private fun addGatewayRow(gw: Gateway) {
        val row = GatewayRowBinding.inflate(layoutInflater, b.gwsContainer, false)
        row.gwDomain.setText(gw.domain)
        row.gwAcid.setText(gw.acid)
        row.gwEnabled.isChecked = gw.enabled
        row.btnDelGw.setOnClickListener {
            b.gwsContainer.removeView(row.root)
            gatewayRows.remove(row)
            markDirty()
        }
        row.gwDomain.addTextChangedListener(dirtyWatcher)
        row.gwAcid.addTextChangedListener(dirtyWatcher)
        row.gwEnabled.setOnCheckedChangeListener { _, _ -> markDirty() }
        gatewayRows.add(row)
        b.gwsContainer.addView(row.root)
    }

    private fun addAccountRow(acc: Account) {
        val row = AccountRowBinding.inflate(layoutInflater, b.accountsContainer, false)
        row.accUser.setText(acc.username)
        row.accPass.setText(acc.password)
        row.accType.setText(acc.userType)
        row.accEnabled.isChecked = acc.enabled
        row.btnDelAcc.setOnClickListener {
            b.accountsContainer.removeView(row.root)
            accountRows.remove(row)
            markDirty()
        }
        row.accUser.addTextChangedListener(dirtyWatcher)
        row.accPass.addTextChangedListener(dirtyWatcher)
        row.accType.addTextChangedListener(dirtyWatcher)
        row.accEnabled.setOnCheckedChangeListener { _, _ -> markDirty() }
        accountRows.add(row)
        b.accountsContainer.addView(row.root)
    }

    private fun addRoomRow(room: RoomWifi) {
        val row = RoomRowBinding.inflate(layoutInflater, b.roomsContainer, false)
        row.roomSsid.setText(room.ssid)
        row.roomPass.setText(room.password)
        row.btnDelRoom.setOnClickListener {
            b.roomsContainer.removeView(row.root)
            roomRows.remove(row)
            markDirty()
        }
        row.roomSsid.addTextChangedListener(dirtyWatcher)
        row.roomPass.addTextChangedListener(dirtyWatcher)
        roomRows.add(row)
        b.roomsContainer.addView(row.root)
    }

    // ------------------------------------------------------------- 保存

    private fun save() {
        val gws = gatewayRows.mapNotNull { row ->
            val domain = row.gwDomain.text.toString().trim()
            if (domain.isEmpty()) return@mapNotNull null
            Gateway(
                domain = domain,
                enabled = row.gwEnabled.isChecked,
                acid = row.gwAcid.text.toString().trim().ifEmpty { "1" },
                n = "200",
                type = "1",
            )
        }
        val accs = accountRows.mapNotNull { row ->
            val username = row.accUser.text.toString().trim()
            if (username.isEmpty()) return@mapNotNull null
            Account(
                username = username,
                password = row.accPass.text.toString(),
                userType = row.accType.text.toString().trim(),
                enabled = row.accEnabled.isChecked,
            )
        }
        val rooms = roomRows.mapNotNull { row ->
            val ssid = row.roomSsid.text.toString().trim()
            if (ssid.isEmpty()) return@mapNotNull null
            RoomWifi(ssid = ssid, password = row.roomPass.text.toString())
        }

        val cfg = AppStore.load(this).copy(
            gateways = gws,
            accounts = accs,
            rooms = rooms,
            pollIntervalSec = b.etPollInterval.text.toString().toIntOrNull()?.coerceIn(5, 86400) ?: 60,
            timeoutMs = b.etTimeout.text.toString().toIntOrNull()?.coerceIn(1000, 30000) ?: 5000,
            renewIntervalSec = b.etRenewInterval.text.toString().toIntOrNull()?.coerceIn(1, 86400) ?: 5,
            loginTimeoutSec = b.etLoginTimeout.text.toString().toIntOrNull()?.coerceIn(2, 600) ?: 10,
            scanIntervalSec = b.etScanInterval.text.toString().toIntOrNull()?.coerceIn(5, 3600) ?: 30,
            targetSsids = b.etTargetSsids.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            metaOs = b.etMetaOs.text.toString().trim().ifEmpty { "Windows 2000" },
            metaName = b.etMetaName.text.toString().trim().ifEmpty { "losedows" },
            autoStart = b.swAutoStart.isChecked,
            showAdvanced = b.swAdvanced.isChecked,
            wifiScan = b.swWifiScan.isChecked,
            batteryWarning = b.swBatteryWarning.isChecked,
            useShizuku = b.swUseShizuku.isChecked,
            autoEnableWifi = b.swAutoEnableWifi.isChecked,
        )
        AppStore.save(this, cfg)

        dirty = false
        updateFab()
        hideKeyboard()
        Toast.makeText(
            this,
            getString(R.string.saved, gws.size, accs.size) + " · " + getString(R.string.saved_rooms, rooms.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------- 脏标记 / 返回

    private fun markDirty() {
        if (!dirty) {
            dirty = true
            updateFab()
        }
    }

    private fun updateFab() {
        b.fabSave.setText(if (dirty) R.string.save_dirty else R.string.save)
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_msg)
            .setNegativeButton(R.string.discard_cancel, null)
            .setPositiveButton(R.string.discard_ok) { _, _ -> finish() }
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"
    }
}
