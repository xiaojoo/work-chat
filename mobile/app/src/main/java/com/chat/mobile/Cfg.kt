package com.chat.mobile

import android.content.Context

/**
 * 后端地址来自**项目配置**：app/build.gradle.kts 里的 API_BASE，
 * 要换机器就在 mobile/local.properties 写一行 api.base=http://192.168.31.5（这文件不提交）。
 * 界面上不再展示、也不再给改这个值——设置页那两行已经撤掉。
 * 登录页那格留着一个逃生口：留空=用项目配置；填了才存成本机覆盖值。
 */
object Cfg {
    private const val PREF = "server"
    private const val KEY_BASE = "api_base"

    /** 编译期烘进包里的地址；adb reverse 在时 127.0.0.1 就是开发机 */
    const val DEFAULT_BASE = BuildConfig.API_BASE

    // 后端四个服务里只有 chat-user(8081) 和消息网关(18082) 是手机端要直连的
    const val USER_PORT = 8081
    const val GW_PORT = 18082
    /** 文件/图片上传走 chat-message 那个服务的 REST，不过网关（网关读帧上限 64KB） */
    const val MSG_PORT = 8083
    /** 群成员 / 邀请在 chat-group 那个服务上，端口和 8081 不是一个，
     *  所以它得有自己的 base（桌面端是 vite 代理把 /api/group 转过去的，手机没有那层代理） */
    const val GROUP_PORT = 8084

    /** 本机没存过覆盖值就用项目配置 */
    fun apiBase(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_BASE, "")!!
            .ifEmpty { DEFAULT_BASE }

    /** 传空串就是把覆盖值清掉，退回项目配置那个地址 */
    fun setApiBase(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, normalize(value)).apply()
    }

    /** 允许填 192.168.31.5、192.168.31.5:8081 或 http://192.168.31.5 三种写法；空串原样返回空 */
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

    /** 消息服务（文件上传/下载）：还是同一个宿主、第三个端口 */
    fun msgBase(ctx: Context): String {
        val base = apiBase(ctx)
        if (base.isEmpty()) return ""
        val scheme = base.substringBefore("://")
        val host = base.substringAfter("://").substringBefore("/").substringBefore(":")
        return "$scheme://$host:$MSG_PORT"
    }

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("token", "") ?: ""

    /** 换票用的那半张票。登录时才有；续期成功要连着新的换回来（后端每次都轮换） */
    fun refreshToken(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("refresh_token", "") ?: ""

    fun setToken(ctx: Context, value: String, userId: String, username: String, refresh: String = "") {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("token", value).putString("userId", userId).putString("username", username)
            .putString("refresh_token", refresh)
            .apply()
    }

    /**
     * 网关对过期的 access 票直接 401（握手就挡），这里拿本机那张 refresh 票换一张新的。
     * 后端是轮换的：响应里 refresh 也换了新的，所以两张都要存回去，
     * 只存 accessToken 的话下一次续期一定失败。换不到返回 null，调用方自己决定别再白敲网关。
     */
    fun renewToken(ctx: Context): String? {
        val rt = refreshToken(ctx)
        if (rt.isEmpty()) return null
        return try {
            val s = Api(apiBase(ctx)).refresh(rt)
            setToken(ctx, s.token, s.userId, s.username, s.refreshToken)
            s.token
        } catch (e: Exception) {
            null
        }
    }

    fun userId(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("userId", "") ?: ""

    fun username(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("username", "") ?: ""

    fun clearToken(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("token").remove("userId").remove("username").apply()
    }

    /**
     * 每台安装一个、杀掉重进不变。原来手机端把 deviceId 写死成 "chat-mobile"，
     * 而网关按 (userId, deviceId) 占一个连接槽：同一个号在两部手机上登录会无限互相顶号。
     */
    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = p.getString("device_id", "") ?: ""
        if (saved.isNotEmpty()) return saved
        val fresh = "mobile-" + java.util.UUID.randomUUID().toString().take(8)
        p.edit().putString("device_id", fresh).apply()
        return fresh
    }

    /** 设置页「字体大小」那一档：1.0=标准、1.15=大、1.3=特大。存本机。 */
    fun fontScale(ctx: Context): Float =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("font_scale", 1f)

    fun setFontScale(ctx: Context, value: Float) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("font_scale", value).apply()
    }

    /** 夜间模式那一档，直接存 AppCompatDelegate 的常量：跟随系统 / 日间 / 夜间。存本机。 */
    fun nightMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt("night_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)

    fun setNightMode(ctx: Context, value: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("night_mode", value).apply()
    }
}
