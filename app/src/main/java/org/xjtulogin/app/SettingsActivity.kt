package org.xjtulogin.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var gwsContainer: LinearLayout
    private lateinit var accountsContainer: LinearLayout
    private val inflater: LayoutInflater get() = layoutInflater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        gwsContainer = findViewById(R.id.gws_container)
        accountsContainer = findViewById(R.id.accounts_container)

        val cfg = AppStore.load(this)

        cfg.gateways.forEach { addGatewayRow(it.domain, it.enabled, it.acid, it.n, it.type) }
        cfg.accounts.forEach { addAccountRow(it.username, it.password, it.userType, it.enabled) }
        if (cfg.gateways.isEmpty()) addGatewayRow("", true, "1", "200", "1")
        if (cfg.accounts.isEmpty()) addAccountRow("", "", "", true)

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_gw).setOnClickListener {
            addGatewayRow("", true, "1", "200", "1")
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_account).setOnClickListener {
            addAccountRow("", "", "", true)
        }

        findViewById<EditText>(R.id.et_poll_interval).setText(cfg.pollIntervalSec.toString())
        findViewById<EditText>(R.id.et_timeout).setText(cfg.timeoutMs.toString())
        findViewById<EditText>(R.id.et_target_ssids).setText(cfg.targetSsids.joinToString(","))
        findViewById<EditText>(R.id.et_room_ssids).setText(cfg.roomSsids.joinToString(","))
        findViewById<EditText>(R.id.et_meta_os).setText(cfg.metaOs)
        findViewById<EditText>(R.id.et_meta_name).setText(cfg.metaName)
        findViewById<MaterialSwitch>(R.id.sw_auto_start).isChecked = cfg.autoStart

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save).setOnClickListener {
            doSave()
        }
    }

    private fun addGatewayRow(domain: String, enabled: Boolean, acid: String, n: String, type: String) {
        val row = inflater.inflate(R.layout.gateway_row, gwsContainer, false)
        row.findViewById<EditText>(R.id.gw_domain).setText(domain)
        row.findViewById<EditText>(R.id.gw_acid).setText(acid)
        row.findViewById<MaterialSwitch>(R.id.gw_enabled).isChecked = enabled
        row.findViewById<ImageButton>(R.id.btn_del_gw).setOnClickListener { gwsContainer.removeView(row) }
        gwsContainer.addView(row)
    }

    private fun addAccountRow(username: String, password: String, userType: String, enabled: Boolean) {
        val row = inflater.inflate(R.layout.account_row, accountsContainer, false)
        row.findViewById<EditText>(R.id.acc_user).setText(username)
        row.findViewById<EditText>(R.id.acc_pass).setText(password)
        row.findViewById<MaterialSwitch>(R.id.acc_enabled).isChecked = enabled
        row.findViewById<ImageButton>(R.id.btn_del_acc).setOnClickListener { accountsContainer.removeView(row) }
        accountsContainer.addView(row)
    }

    private fun doSave() {
        val gws = mutableListOf<Gateway>()
        for (i in 0 until gwsContainer.childCount) {
            val row = gwsContainer.getChildAt(i)
            val domain = row.findViewById<EditText>(R.id.gw_domain).text.toString().trim()
            if (domain.isEmpty()) continue
            gws.add(
                Gateway(
                    domain = domain,
                    enabled = row.findViewById<MaterialSwitch>(R.id.gw_enabled).isChecked,
                    acid = row.findViewById<EditText>(R.id.gw_acid).text.toString().trim().ifEmpty { "1" },
                    n = "200",
                    type = "1",
                )
            )
        }
        val accs = mutableListOf<Account>()
        for (i in 0 until accountsContainer.childCount) {
            val row = accountsContainer.getChildAt(i)
            val username = row.findViewById<EditText>(R.id.acc_user).text.toString().trim()
            if (username.isEmpty()) continue
            accs.add(
                Account(
                    username = username,
                    password = row.findViewById<EditText>(R.id.acc_pass).text.toString(),
                    enabled = row.findViewById<MaterialSwitch>(R.id.acc_enabled).isChecked,
                )
            )
        }

        val cfg = AppStore.load(this).copy(
            gateways = gws,
            accounts = accs,
            pollIntervalSec = findViewById<EditText>(R.id.et_poll_interval).text.toString().toIntOrNull()?.coerceIn(5, 86400) ?: 60,
            timeoutMs = findViewById<EditText>(R.id.et_timeout).text.toString().toIntOrNull()?.coerceIn(1000, 30000) ?: 5000,
            targetSsids = findViewById<EditText>(R.id.et_target_ssids).text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            roomSsids = findViewById<EditText>(R.id.et_room_ssids).text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            metaOs = findViewById<EditText>(R.id.et_meta_os).text.toString().trim().ifEmpty { "Windows 2000" },
            metaName = findViewById<EditText>(R.id.et_meta_name).text.toString().trim().ifEmpty { "losedows" },
            autoStart = findViewById<MaterialSwitch>(R.id.sw_auto_start).isChecked,
        )
        AppStore.save(this, cfg)
        Toast.makeText(this, "已保存（${gws.size} 网关 / ${accs.size} 账号）", Toast.LENGTH_SHORT).show()
    }

    @Suppress("unused")
    private fun noop(v: View) {
    }
}