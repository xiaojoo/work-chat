package com.chat.mobile

import android.content.Context

/**
 * 服务器地址。手机上 127.0.0.1 是手机自己，连不到开发机，
 * 所以地址必须能在界面上改、并存在本机；不给编译期默认值。
 */
object Cfg {
    private const val PREF = "server"
    private const val KEY_BASE = "api_base"

    // 后端四个服务里只有 chat-user(8081) 和消息网关(18082) 是手机端要直连的
    const val USER_PORT = 8081
    const val GW_PORT = 18082
    /** 群成员 / 邀请在 chat-group 那个服务上，端口和 8081 不是一个，
     *  所以它得有自己的 base（桌面端是 vite 代理把 /api/group 转过去的，手机没有那层代理） */
    const val GROUP_PORT = 8084

    fun apiBase(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_BASE, "") ?: ""

    fun setApiBase(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, normalize(value)).apply()
    }

    /** 允许他填 192.168.31.5、192.168.31.5:8081 或 http://192.168.31.5 三种写法 */
    fun normalize(raw: String): String {
        var s = raw.trim().removeSuffix("/")
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val withoutScheme = s.substringAfter("://")
        val hostPart = withoutScheme.substringBefore("/")
        val hasPort = hostPart.contains(":")
        return if (hasPort) s else "${s.substringBefore("://")}://$hostPart:$USER_PORT"
    }

    /** 会话推送走 Go 网关，端口和 http 口不在一起，从 apiBase 的宿主推出来 */
    fun wsBase(ctx: Context): String {
        val base = apiBase(ctx)
        if (base.isEmpty()) return ""
        val host = base.substringAfter("://").substringBefore("/").substringBefore(":")
        val scheme = if (base.startsWith("https")) "wss" else "ws"
        return "$scheme://$host:$GW_PORT/ws"
    }

    /** 群服务：同一个宿主、另一个端口，和 wsBase 一个推法 */
    fun groupBase(ctx: Context): String {
        val base = apiBase(ctx)
        if (base.isEmpty()) return ""
        val scheme = base.substringBefore("://")
        val host = base.substringAfter("://").substringBefore("/").substringBefore(":")
        return "$scheme://$host:$GROUP_PORT"
    }

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun setToken(ctx: Context, value: String, userId: String, username: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("token", value).putString("userId", userId).putString("username", username)
            .apply()
    }

    fun userId(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("userId", "") ?: ""

    fun username(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("username", "") ?: ""

    fun clearToken(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("token").remove("userId").remove("username").apply()
    }
}
