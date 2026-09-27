package org.xjtulogin.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNeededPermissions()

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_start).setOnClickListener {
            val cfg = AppStore.load(this).copy(serviceEnabled = true)
            AppStore.save(this, cfg)
            startForegroundService(Intent(this, LoginService::class.java))
            Toast.makeText(this, "保活已启动", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_stop).setOnClickListener {
            val cfg = AppStore.load(this).copy(serviceEnabled = false)
            AppStore.save(this, cfg)
            stopService(Intent(this, LoginService::class.java))
            LoginStatusHolder.update("已停止", "手动停止", "stopped by user")
            Toast.makeText(this, "保活已停止", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_refresh).setOnClickListener {
            LoginStatusHolder.update("检测中…", "", "")
            startForegroundService(Intent(this, LoginService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 100)
        }
    }

    private fun refresh() {
        if (AppStore.load(this).serviceEnabled) {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_start).isEnabled = false
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_stop).isEnabled = true
        } else {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_start).isEnabled = true
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_stop).isEnabled = false
        }

        val ts = LoginStatusHolder.lastAt
        val time = if (ts > 0)
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)) else "—"
        findViewById<android.widget.TextView>(R.id.status_main).text =
            LoginStatusHolder.summary + "（$time）"
        findViewById<android.widget.TextView>(R.id.status_detail).text = LoginStatusHolder.detail
        findViewById<android.widget.TextView>(R.id.status_log).text = LoginStatusHolder.log
    }
}