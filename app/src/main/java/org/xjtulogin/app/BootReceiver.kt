package org.xjtulogin.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val cfg = AppStore.load(context)
                if (cfg.autoStart && cfg.serviceEnabled) {
                    LoginService.start(context)
                }
            }
        }
    }
}
