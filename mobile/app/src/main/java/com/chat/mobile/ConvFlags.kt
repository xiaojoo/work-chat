package com.chat.mobile

import android.content.Context

/**
 * 置顶 / 免打扰 / 不显示这三件事后端没有字段（桌面端也是纯本地 flag），所以存在本机。
 * 和桌面端差一点：桌面端刷新就丢，这里存 SharedPreferences，杀掉重进还在。
 *
 * 「不显示」按微信的语义：只藏到这条会话又来新消息为止，所以隐藏时记下当时的 lastMessageTime，
 * 下次拉到不一样的时间就自动回来——不然会话就是凭空蒸发，再也找不回来。
 */
class ConvFlags(ctx: Context) {

    private val p = ctx.getSharedPreferences("conv_flags", Context.MODE_PRIVATE)

    fun pinned(id: String): Boolean = p.getBoolean("pin$id", false)
    fun togglePinned(id: String) { p.edit().putBoolean("pin$id", !pinned(id)).apply() }

    fun muted(id: String): Boolean = p.getBoolean("mute$id", false)
    fun toggleMuted(id: String) { p.edit().putBoolean("mute$id", !muted(id)).apply() }

    fun hide(id: String, at: String) { p.edit().putString("hide$id", at).apply() }

    fun hidden(id: String, lastTime: String): Boolean {
        val at = p.getString("hide$id", null) ?: return false
        if (at == lastTime) return true
        p.edit().remove("hide$id").apply()
        return false
    }

    fun hiddenCount(): Int = p.all.keys.count { it.startsWith("hide") }

    /** 删除之后把这条会话的所有本地痕迹清掉：会话 id 是雪花，不复用，但留着就是死数据 */
    fun forget(id: String) {
        p.edit().remove("pin$id").remove("mute$id").remove("hide$id").apply()
    }
}
