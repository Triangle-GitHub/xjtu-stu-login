// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep

/**
 * Shizuku UserService：本类**不在 App 进程里跑**，而是由 Shizuku 服务端拉起一个新进程、
 * 以 shell(2000) 或 root(0) 身份加载本类（构造器会被反射调用 ⇒ 必须 `@Keep` 且不可被 R8 改名）。
 *
 * 为什么不用 `Shizuku.newProcess`：它自 13.1.1 起已废弃并转为私有，官方明确要求改用 UserService。
 */
class ShellUserService() : IShellUserService.Stub() {

    /**
     * Shizuku v13 会优先用带 Context 的构造器（拿不到就退回无参），两个都得在。
     * 直接在此声明主构造器：否则只有次级构造器时 Kotlin 不允许初始化父类。
     */
    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?) : this()

    override fun destroy() {
        // Shizuku 约定的销毁入口：自己退出，别留僵尸进程
        Process.killProcess(Process.myPid())
    }

    override fun uid(): Int = Process.myUid()

    override fun exec(cmd: String): String {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(true)
            // UserService 进程的 PATH 不保证含 /system/bin，显式给全
            pb.environment()["PATH"] = "/system/bin:/system/xbin:/product/bin:/vendor/bin"
            val p = pb.start()

            // 看门狗：命令卡住（比如 wifi 服务无响应）也不能把 App 的调用线程吊死
            val watchdog = Thread {
                try {
                    Thread.sleep(CMD_TIMEOUT_MS)
                    p.destroy()
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true }
            watchdog.start()

            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            watchdog.interrupt()
            "exit=$code\n$out"
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: ${t.message}")
            "exit=-1\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private companion object {
        const val TAG = "XjtuShellUserService"
        const val CMD_TIMEOUT_MS = 10_000L
    }
}
