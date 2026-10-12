package org.xjtulogin.app

import android.content.Context

enum class StatusLevel { IDLE, ONLINE, OFFLINE, WARN }

data class ServiceStatus(
    val summary: String = "未启动",
    val detail: String = "",
    val log: String = "",
    val level: StatusLevel = StatusLevel.IDLE,
    val updatedAt: Long = 0L,
    val heartbeatAt: Long = 0L,
)

/**
 * 保活运行态：内存缓存 + 明文 prefs 落盘。
 *
 * 落盘是为了「进程被杀后再打开 App」仍能看到最后状态；heartbeat 用来判断
 * 前台服务到底还活着没有（App 靠它做「进程被回收 → 回前台自愈」）。
 * 这里不含任何凭据，所以用普通 prefs 即可（避免每次读写都过 Keystore）。
 */
object StatusStore {
    private const val PREF = "xjtu_status"
    private const val K_SUMMARY = "summary"
    private const val K_DETAIL = "detail"
    private const val K_LOG = "log"
    private const val K_LEVEL = "level"
    private const val K_UPDATED = "updated_at"
    private const val K_HEARTBEAT = "heartbeat_at"

    @Volatile private var cache: ServiceStatus? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(ctx: Context): ServiceStatus {
        cache?.let { return it }
        val p = try {
            prefs(ctx)
        } catch (_: Throwable) {
            return ServiceStatus()
        }
        val s = ServiceStatus(
            summary = p.getString(K_SUMMARY, null) ?: ctx.getString(R.string.status_idle),
            detail = p.getString(K_DETAIL, null) ?: "",
            log = p.getString(K_LOG, null) ?: "",
            level = runCatching { StatusLevel.valueOf(p.getString(K_LEVEL, null) ?: "IDLE") }
                .getOrDefault(StatusLevel.IDLE),
            updatedAt = p.getLong(K_UPDATED, 0L),
            heartbeatAt = p.getLong(K_HEARTBEAT, 0L),
        )
        cache = s
        return s
    }

    fun update(ctx: Context, summary: String, detail: String, log: String, level: StatusLevel) {
        val now = System.currentTimeMillis()
        persist(
            ctx,
            ServiceStatus(summary, detail, log.takeLast(4000), level, now, now)
        )
    }

    /** 只刷新心跳（本轮被跳过、没有新结论时用） */
    fun beat(ctx: Context) {
        val cur = get(ctx)
        persist(ctx, cur.copy(heartbeatAt = System.currentTimeMillis()))
    }

    /** 前台服务是否还在按节奏心跳 */
    fun isAlive(ctx: Context, pollIntervalSec: Int): Boolean {
        val s = get(ctx)
        if (s.heartbeatAt <= 0L) return false
        val staleAfter = (pollIntervalSec.coerceIn(5, 86400) * 2500L).coerceAtLeast(150_000L)
        return System.currentTimeMillis() - s.heartbeatAt < staleAfter
    }

    private fun persist(ctx: Context, s: ServiceStatus) {
        cache = s
        try {
            prefs(ctx).edit()
                .putString(K_SUMMARY, s.summary)
                .putString(K_DETAIL, s.detail)
                .putString(K_LOG, s.log)
                .putString(K_LEVEL, s.level.name)
                .putLong(K_UPDATED, s.updatedAt)
                .putLong(K_HEARTBEAT, s.heartbeatAt)
                .apply()
        } catch (_: Throwable) {
        }
    }
}
