// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app;

/**
 * 在 Shizuku 拉起的 **UserService 进程**（uid = shell 2000 / root 0）里执行 shell。
 *
 * 对应原 Termux 脚本里的 `$ADB shell <cmd>` —— 同一套命令、同一个身份，
 * 只是把 adb 通道换成了 Shizuku 的 binder 通道。
 *
 * `destroy` 的事务号 16777114 由 Shizuku 服务端约定（见 Shizuku-API README），不要改。
 */
interface IShellUserService {
    void destroy() = 16777114;

    /** 执行一条 shell 命令；返回 `exit=<code>\n<stdout+stderr>` */
    String exec(String cmd) = 1;

    /** 当前进程 uid：0=root，2000=shell */
    int uid() = 2;
}
