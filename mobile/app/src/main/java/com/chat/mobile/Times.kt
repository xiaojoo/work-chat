package com.chat.mobile

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 后端两个时间字段格式不一样，实测：
 *   会话列表 lastMessageTime = 2026-09-26T23:52:10.917246   （不带 offset）
 *   消息 createTime          = 2026-09-27T05:06:30.586Z     （带 Z）
 * 桌面端 formatConvTime 走 new Date(...)，JS 对不带 offset 的日期时间串按本地时间解释，
 * 所以这里也按本地时间吃；带 Z 的那类换算到设备时区。语义照桌面：今天时分、昨天、MM-DD。
 */
object Times {

    fun conv(ts: String?, now: LocalDate = LocalDate.now()): String {
        if (ts.isNullOrBlank()) return ""
        val d = parse(ts) ?: return ""
        val day = d.toLocalDate()
        return when {
            day == now -> d.format(DateTimeFormatter.ofPattern("HH:mm"))
            day == now.minusDays(1) -> "昨天"
            day.year == now.year -> d.format(DateTimeFormatter.ofPattern("M月d日"))
            else -> d.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        }
    }

    private fun parse(s: String): LocalDateTime? = try {
        if (s.endsWith("Z") || s.contains("+"))
            OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        else
            LocalDateTime.parse(s)
    } catch (e: Exception) { null }
}
