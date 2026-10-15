// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 通道 —— 本 App 里「原脚本 `$ADB shell`」的等价物。
 *
 * 原脚本靠手机上的 adb（`adb -s $DEVICE shell cmd wifi …`）拿到 **shell(2000)** 身份；
 * Shizuku 用 binder 把同一身份交给 App：命令一模一样，但不需要开 5555、不需要电脑、不需要 Termux。
 *
 * 生命周期要点：
 * 1. 用户需在 Shizuku 里授权本 App（`requestPermission`）—— 一次性；
 * 2. 首次用到时 `bindUserService` 拉起 [ShellUserService]（uid 2000/0 的新进程），之后复用；
 * 3. Shizuku 的 binder 可能死掉（去激活/重启手机）⇒ 监听 `addBinderDeadListener` 清缓存，下次自动重绑；
 * 4. 全程不抛异常：拿不到就返回 null，由调用方降级（状态机里写 DEBG/WARN 日志）。
 */
object ShizukuShell {

    private const val TAG = "XjtuShizuku"
    private const val USER_SERVICE_VERSION = 1
    private const val USER_SERVICE_TAG = "xjtu-shell"
    private const val BIND_TIMEOUT_MS = 6_000L

    /** 授权请求码（给 [Shizuku.requestPermission] / 结果回调用） */
    const val PERMISSION_REQUEST_CODE = 0x5A17

    @Volatile private var service: IShellUserService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val lock = Any()
    @Volatile private var listenersRegistered = false
    @Volatile private var lastError: String? = null

    // ---------------------------------------------------------------- 状态查询

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** Shizuku 活着且已授权本 App */
    fun ready(): Boolean = hasPermission()

    fun hasPermission(): Boolean = try {
        if (!binderAlive() || Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun shouldShowRationale(): Boolean = try {
        Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            ensureListeners()
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            lastError = t.message
        }
    }

    fun addPermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(l)
        } catch (_: Throwable) {
        }
    }

    fun removePermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(l)
        } catch (_: Throwable) {
        }
    }

    fun lastError(): String? = lastError

    /** 拿到 shell 进程的 uid（2000=shell，0=root）；拿不到返回 -1 */
    fun uid(): Int = try {
        service?.uid() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun describe(): String = when {
        !binderAlive() -> "Shizuku 未运行"
        !hasPermission() -> "Shizuku 未授权"
        else -> "Shizuku 已授权(uid=${uid().takeIf { it >= 0 } ?: "待绑定"})"
    }

    // ---------------------------------------------------------------- 执行

    data class Result(val exit: Int, val output: String) {
        val ok: Boolean get() = exit == 0
    }

    /**
     * 跑一条 shell 命令；Shizuku 不可用/未授权/绑定失败都返回 null（调用方据此降级）。
     * 返回文本同时含 stdout 与 stderr（`redirectErrorStream`）。
     */
    fun run(ctx: Context, cmd: String): Result? {
        val svc = ensureService(ctx) ?: return null
        return try {
            parse(svc.exec(cmd))
        } catch (t: Throwable) {
            lastError = t.message
            service = null
            connection = null
            null
        }
    }

    private fun parse(raw: String?): Result {
        if (raw == null) return Result(-1, "")
        val nl = raw.indexOf('\n')
        val head = if (nl >= 0) raw.substring(0, nl) else raw
        val body = if (nl >= 0) raw.substring(nl + 1) else ""
        val code = head.removePrefix("exit=").trim().toIntOrNull() ?: -1
        return Result(code, body)
    }

    private fun ensureService(ctx: Context): IShellUserService? {
        service?.let { return it }
        if (!hasPermission()) return null
        synchronized(lock) {
            service?.let { return it }
            ensureListeners()
            val app = ctx.applicationContext
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IShellUserService.Stub.asInterface(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            return try {
                val args = UserServiceArgs(
                    ComponentName(app.packageName, ShellUserService::class.java.name)
                )
                    .daemon(false)
                    .version(USER_SERVICE_VERSION)
                    .tag(USER_SERVICE_TAG)
                Shizuku.bindUserService(args, conn)
                connection = conn
                latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                service
            } catch (t: Throwable) {
                lastError = t.message
                Log.w(TAG, "bindUserService failed: ${t.message}")
                connection = null
                null
            }
        }
    }

    private fun ensureListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        try {
            Shizuku.addBinderDeadListener {
                Log.i(TAG, "shizuku binder dead")
                service = null
                connection = null
            }
        } catch (_: Throwable) {
            listenersRegistered = false
        }
    }
}
