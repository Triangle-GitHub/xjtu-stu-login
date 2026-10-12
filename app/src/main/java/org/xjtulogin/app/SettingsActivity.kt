package org.xjtulogin.app

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

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var dirty = false
    private var advancedOpen = false

    private val dirtyWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            markDirty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupHeaders()
        bindConfig()
        attachListeners()
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
        if (cfg.gateways.isEmpty()) addGatewayRow(Gateway())
        if (cfg.accounts.isEmpty()) addAccountRow(Account(userType = "stu"))

        b.etPollInterval.setText(cfg.pollIntervalSec.toString())
        b.etTimeout.setText(cfg.timeoutMs.toString())
        b.etTargetSsids.setText(cfg.targetSsids.joinToString(","))
        b.etRoomSsids.setText(cfg.roomSsids.joinToString(","))
        b.etMetaOs.setText(cfg.metaOs)
        b.etMetaName.setText(cfg.metaName)
        b.swAutoStart.isChecked = cfg.autoStart

        // 监视放在填充之后：避免初始化就标脏
        listOf(
            b.etPollInterval, b.etTimeout, b.etTargetSsids,
            b.etRoomSsids, b.etMetaOs, b.etMetaName
        ).forEach { it.addTextChangedListener(dirtyWatcher) }
        b.swAutoStart.setOnCheckedChangeListener { _, _ -> markDirty() }
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
        b.fabSave.setOnClickListener { save() }
    }

    // ------------------------------------------------------------- 动态行

    private fun addGatewayRow(gw: Gateway) {
        val row = GatewayRowBinding.inflate(layoutInflater, b.gwsContainer, false)
        row.gwDomain.setText(gw.domain)
        row.gwAcid.setText(gw.acid)
        row.gwEnabled.isChecked = gw.enabled
        row.btnDelGw.setOnClickListener {
            b.gwsContainer.removeView(row.root)
            markDirty()
        }
        row.gwDomain.addTextChangedListener(dirtyWatcher)
        row.gwAcid.addTextChangedListener(dirtyWatcher)
        row.gwEnabled.setOnCheckedChangeListener { _, _ -> markDirty() }
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
            markDirty()
        }
        row.accUser.addTextChangedListener(dirtyWatcher)
        row.accPass.addTextChangedListener(dirtyWatcher)
        row.accType.addTextChangedListener(dirtyWatcher)
        row.accEnabled.setOnCheckedChangeListener { _, _ -> markDirty() }
        b.accountsContainer.addView(row.root)
    }

    // ------------------------------------------------------------- 保存

    private fun save() {
        val gws = (0 until b.gwsContainer.childCount).mapNotNull { i ->
            val row = b.gwsContainer.getChildAt(i)
            val domain = row.findViewById<android.widget.EditText>(R.id.gw_domain).text.toString().trim()
            if (domain.isEmpty()) return@mapNotNull null
            Gateway(
                domain = domain,
                enabled = row.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.gw_enabled).isChecked,
                acid = row.findViewById<android.widget.EditText>(R.id.gw_acid).text.toString().trim().ifEmpty { "1" },
                n = "200",
                type = "1",
            )
        }
        val accs = (0 until b.accountsContainer.childCount).mapNotNull { i ->
            val row = b.accountsContainer.getChildAt(i)
            val username = row.findViewById<android.widget.EditText>(R.id.acc_user).text.toString().trim()
            if (username.isEmpty()) return@mapNotNull null
            Account(
                username = username,
                password = row.findViewById<android.widget.EditText>(R.id.acc_pass).text.toString(),
                userType = row.findViewById<android.widget.EditText>(R.id.acc_type).text.toString().trim(),
                enabled = row.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.acc_enabled).isChecked,
            )
        }

        val cfg = AppStore.load(this).copy(
            gateways = gws,
            accounts = accs,
            pollIntervalSec = b.etPollInterval.text.toString().toIntOrNull()?.coerceIn(5, 86400) ?: 60,
            timeoutMs = b.etTimeout.text.toString().toIntOrNull()?.coerceIn(1000, 30000) ?: 5000,
            targetSsids = b.etTargetSsids.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            roomSsids = b.etRoomSsids.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            metaOs = b.etMetaOs.text.toString().trim().ifEmpty { "Windows 2000" },
            metaName = b.etMetaName.text.toString().trim().ifEmpty { "losedows" },
            autoStart = b.swAutoStart.isChecked,
        )
        AppStore.save(this, cfg)

        dirty = false
        updateFab()
        hideKeyboard()
        Toast.makeText(this, getString(R.string.saved, gws.size, accs.size), Toast.LENGTH_SHORT).show()
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
}
