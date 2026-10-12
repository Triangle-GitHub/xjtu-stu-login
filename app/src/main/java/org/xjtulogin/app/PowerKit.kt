package org.xjtulogin.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** 保活相关系统设置（电池优化白名单 / 通知权限） */
object PowerKit {

    /** 已忽略电池优化（= 允许后台长期运行） */
    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try {
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Throwable) {
            true
        }
    }

    /** 拉起「忽略电池优化」授权页；部分 ROM 无该页时退回电池优化列表 */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )
        val started = try {
            activity.startActivity(direct)
            true
        } catch (_: Throwable) {
            false
        }
        if (!started) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    fun notificationsEnabled(ctx: Context): Boolean = try {
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    } catch (_: Throwable) {
        true
    }

    fun openNotificationSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Throwable) {
            openAppSettings(ctx)
        }
    }

    fun openAppSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}
