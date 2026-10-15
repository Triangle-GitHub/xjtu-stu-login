// SPDX-License-Identifier: GPL-3.0-only
package org.xjtulogin.app

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日志层：**逐字对齐原 Termux 脚本 `keep_srun_v2.6.sh`**。
 *
 * 原脚本的 `log()` 输出格式：
 * ```
 * 2026-09-13                      ← 日期变化时先打一行（灰）
 * 14:23:45 [INFO] 校园网已连接，尝试登录
 * ```
 * 其中时间戳灰、`[`/`]` 白、等级按级别上色（INFO 青 / STAT 绿 / WARN 黄 / ERRO+FATA 红 / DEBG 深灰）、
 * 正文用默认前景色。这里保留同一套格式与等级名，颜色落到 Android 的 span 上
 * （xterm 调色板近似值，见 res/values/colors.xml 的 `log_*`）。
 *
 * 之所以要把等级独立成枚举而不是拼字符串：界面要用 span 上色，通知/复制要纯文本 —— 两种渲染同源。
 */
@Suppress("EnumEntryName")
enum class LogLevel(val label: String, @ColorRes val colorRes: Int) {
    /** 原脚本 INFO（青） */
    INFO("INFO", R.color.log_info),

    /** 原脚本 STAT（绿）：状态机切换 */
    STAT("STAT", R.color.log_stat),

    /** 原脚本 WARN（黄） */
    WARN("WARN", R.color.log_warn),

    /** 原脚本 ERRO（红） */
    ERRO("ERRO", R.color.log_error),

    /** 原脚本 FATA（红）：致命前置错误 */
    FATA("FATA", R.color.log_error),

    /** 原脚本 DEBG（深灰）：本 App 附加的诊断细节（不属原脚本，刻意压低存在感） */
    DEBG("DEBG", R.color.log_debug),
}

/** 一条日志 = 时间戳 + 等级 + 正文（正文内不含换行） */
data class LogLine(val at: Long, val level: LogLevel, val msg: String)

object LogKit {

    /** 环形缓冲上限：原脚本是终端滚动，这里给个够长的窗口（展开时能看很久） */
    const val MAX_LINES = 400

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun time(at: Long): String =
        TIME_FMT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

    private fun date(at: Long): String =
        DATE_FMT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

    private fun isSameDay(a: Long, b: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    }

    /** 一条日志的纯文本形态（不含日期行）：`14:23:45 [INFO] msg` */
    fun plainLine(line: LogLine): String = "${time(line.at)} [${line.level.label}] ${line.msg}"

    /** 多行纯文本（含日期分隔行）—— 复制日志 / 通知折叠文案都用它 */
    fun plainText(lines: List<LogLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        var lastAt: Long? = null
        for (l in lines) {
            if (lastAt == null || !isSameDay(lastAt, l.at)) sb.append(date(l.at)).append('\n')
            sb.append(plainLine(l)).append('\n')
            lastAt = l.at
        }
        return sb.toString()
    }

    /**
     * 上色渲染（给 TextView）。
     * 行内：时间戳灰 → `[` 白 → 等级着色 → `]` 白 → 正文默认色；跨日期插一行灰色日期。
     */
    fun render(ctx: Context, lines: List<LogLine>): CharSequence {
        val out = SpannableStringBuilder()
        if (lines.isEmpty()) return out

        val cTime = ContextCompat.getColor(ctx, R.color.log_time)
        val cBracket = ContextCompat.getColor(ctx, R.color.log_bracket)

        var lastAt: Long? = null
        for (l in lines) {
            if (lastAt == null || !isSameDay(lastAt, l.at)) {
                appendColored(out, date(l.at), cTime)
                out.append('\n')
            }
            appendColored(out, time(l.at), cTime)
            out.append(' ')
            appendColored(out, "[", cBracket)
            appendColored(out, l.level.label, ContextCompat.getColor(ctx, l.level.colorRes))
            appendColored(out, "]", cBracket)
            out.append(' ').append(l.msg.replace('\n', ' ')).append('\n')
            lastAt = l.at
        }
        return out
    }

    private fun appendColored(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ------------------------------------------------------------ 落盘编码

    /** `at\tLEVEL\tmsg`，行间 `\n`（msg 里的换行已折成空格，所以解码无歧义） */
    fun encode(lines: List<LogLine>): String = lines.joinToString("\n") { l ->
        "${l.at}\t${l.level.name}\t${l.msg.replace('\n', ' ')}"
    }

    fun decode(raw: String?): List<LogLine> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { row ->
            val p1 = row.indexOf('\t')
            if (p1 <= 0) return@mapNotNull null
            val p2 = row.indexOf('\t', p1 + 1)
            if (p2 <= p1) return@mapNotNull null
            val at = row.substring(0, p1).toLongOrNull() ?: return@mapNotNull null
            val level = runCatching { LogLevel.valueOf(row.substring(p1 + 1, p2)) }.getOrNull()
                ?: return@mapNotNull null
            LogLine(at, level, row.substring(p2 + 1))
        }.toList()
    }
}
