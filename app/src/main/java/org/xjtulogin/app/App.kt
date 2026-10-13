// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 通知渠道在 Application 阶段就建好：服务被系统重启/进程回收后状态栏通知不丢
        LoginService.ensureChannel(this)
    }
}
