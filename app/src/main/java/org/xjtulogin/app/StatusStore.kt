// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context

enum class StatusLevel { IDLE, ONLINE, OFFLINE, WARN }

data class ServiceStatus(
    val summary: String = "未启动",
    val detail: String = "",
    val level: StatusLevel = StatusLevel.IDLE,
    val updatedAt: Long = 0L,
    val heartbeatAt: Long = 0L,
    /** 当前 WiFi 名（可为空：无 WiFi 或读不到） */
    val ssid: String? = null,
    /** 当前/认证 IP */
    val ip: String? = null,
    /** 运行日志（逐条带等级，界面用 span 上色） */
    val lines: List<LogLine> = emptyList(),
) {
    /** 纯文本日志（不含颜色）：通知折叠文案与「复制日志」用 */
    val log: String get() = LogKit.plainText(lines)
}

/**
 * 通知里只放最后几行（原脚本是在终端里滚动，通知栏放全会截断又浪费）；
 * [`log`] 仍是完整文本，供「复制日志」与故障排查用。
 */
fun ServiceStatus.logTail(n: Int): String = LogKit.plainText(lines.takeLast(n))

/**
 * 保活运行态：内存缓存 + 明文 prefs 落盘。
 *
 * 落盘是为了「进程被杀后再打开 App」仍能看到最后状态；heartbeat 用来判断
 * 前台服务到底还活着没有（App 靠它做「进程被回收 → 回前台自愈」）。
 * 这里不含任何凭据，所以用普通 prefs 即可（避免每次读写都过 Keystore）。
 *
 * 与原脚本的对应：`lines` 就是终端里滚动的那些行（格式/等级/颜色见 [LogKit]）；
 * 状态机的每个 `log` 调用都会 append 一条，界面每 1.5s 重画一次 ⇒ 相当于「看到脚本在跑」。
 */
object StatusStore {
    private const val PREF = "xjtu_status"
    private const val K_SUMMARY = "summary"
    private const val K_DETAIL = "detail"
    private const val K_LOG_LINES = "log_lines"
    private const val K_LEVEL = "level"
    private const val K_UPDATED = "updated_at"
    private const val K_HEARTBEAT = "heartbeat_at"
    private const val K_SSID = "ssid"
    private const val K_IP = "ip"

    /** 兼容旧版本：曾经把纯文本日志存在这个键里 */
    private const val K_LOG_LEGACY = "log"

    @Volatile private var cache: ServiceStatus? = null

    /**
     * 落盘去抖：状态机每 5s 就会 append 一行，如果每行都把整个 prefs 文件重写一遍
     * （400 行 ≈ 15KB，一小时约 10MB 写入）对闪存和电量都不划算。
     * 内存里的 [ServiceStatus] 始终是最新的（界面照旧实时），只是**落盘**最多 2s 一次；
     * 心跳同理 —— 即使落后几秒，MainActivity 的存活判定（≥150s）也足够宽松。
     */
    private const val PERSIST_MIN_INTERVAL_MS = 2_000L

    /** 只落盘最近这么多行（进程被杀后回来看得到尾巴即可；会话内界面仍可看全量） */
    private const val PERSIST_LINES = 60

    @Volatile private var lastPersistAt = 0L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(ctx: Context): ServiceStatus {
        cache?.let { return it }
        val p = try {
            prefs(ctx)
        } catch (_: Throwable) {
            return ServiceStatus()
        }
        val lines = LogKit.decode(p.getString(K_LOG_LINES, null)).ifEmpty {
            // 老版本留下的纯文本日志：原样收进一条 DEBG，别让它消失
            p.getString(K_LOG_LEGACY, null)?.takeIf { it.isNotBlank() }?.let {
                listOf(LogLine(System.currentTimeMillis(), LogLevel.DEBG, it.takeLast(2000)))
            } ?: emptyList()
        }
        val s = ServiceStatus(
            summary = p.getString(K_SUMMARY, null) ?: ctx.getString(R.string.status_idle),
            detail = p.getString(K_DETAIL, null) ?: "",
            level = runCatching { StatusLevel.valueOf(p.getString(K_LEVEL, null) ?: "IDLE") }
                .getOrDefault(StatusLevel.IDLE),
            updatedAt = p.getLong(K_UPDATED, 0L),
            heartbeatAt = p.getLong(K_HEARTBEAT, 0L),
            ssid = p.getString(K_SSID, null),
            ip = p.getString(K_IP, null),
            lines = lines,
        )
        cache = s
        return s
    }

    /** 更新状态卡（摘要/详情/等级）；日志不动 */
    fun setStatus(
        ctx: Context,
        summary: String,
        detail: String,
        level: StatusLevel,
        ssid: String? = null,
        ip: String? = null,
        beat: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val cur = get(ctx)
        persist(
            ctx,
            cur.copy(
                summary = summary,
                detail = detail,
                level = level,
                updatedAt = now,
                heartbeatAt = if (beat) now else cur.heartbeatAt,
                ssid = ssid ?: cur.ssid,
                ip = ip ?: cur.ip,
            )
        )
    }

    /** 追加一行日志（等价原脚本的一次 `log`），同时刷新心跳 */
    fun append(ctx: Context, level: LogLevel, msg: String) {
        appendLine(ctx, LogLine(System.currentTimeMillis(), level, msg))
    }

    fun appendLine(ctx: Context, line: LogLine) {
        val now = System.currentTimeMillis()
        val cur = get(ctx)
        val next = (cur.lines + line).takeLast(LogKit.MAX_LINES)
        persist(ctx, cur.copy(lines = next, updatedAt = now, heartbeatAt = now), force = false)
    }

    /** 仅刷新心跳：状态机在长等待里也要让界面知道「服务还活着」 */
    fun beat(ctx: Context) {
        val cur = get(ctx)
        persist(ctx, cur.copy(heartbeatAt = System.currentTimeMillis()), force = false)
    }

    /** 清空日志（单次登录 / 重新启动保活时想让日志干净些） */
    fun clearLog(ctx: Context) {
        val cur = get(ctx)
        persist(ctx, cur.copy(lines = emptyList()))
    }

    /** 前台服务是否还在按节奏心跳 */
    fun isAlive(ctx: Context, intervalSec: Int): Boolean {
        val s = get(ctx)
        if (s.heartbeatAt <= 0L) return false
        val staleAfter = (intervalSec.coerceIn(5, 86400) * 2500L).coerceAtLeast(150_000L)
        return System.currentTimeMillis() - s.heartbeatAt < staleAfter
    }

    private fun persist(ctx: Context, s: ServiceStatus, force: Boolean = true) {
        cache = s
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_MIN_INTERVAL_MS) return
        lastPersistAt = now
        try {
            prefs(ctx).edit()
                .putString(K_SUMMARY, s.summary)
                .putString(K_DETAIL, s.detail)
                .putString(K_LOG_LINES, LogKit.encode(s.lines.takeLast(PERSIST_LINES)))
                .putString(K_LEVEL, s.level.name)
                .putLong(K_UPDATED, s.updatedAt)
                .putLong(K_HEARTBEAT, s.heartbeatAt)
                .putString(K_SSID, s.ssid)
                .putString(K_IP, s.ip)
                .remove(K_LOG_LEGACY)
                .apply()
        } catch (_: Throwable) {
        }
    }
}
