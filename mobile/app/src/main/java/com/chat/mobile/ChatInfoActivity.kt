package com.chat.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 聊天页右上角 ⋯ 进来的那一页。
 *
 * 微信这一屏还有成员宫格、查找聊天记录、提醒、设置聊天背景、清空聊天记录、投诉——
 * 后端一个都没有对应接口（成员在 chat-group 那个服务，手机端目前只连 chat-user 和网关），
 * 所以这里只放真做得到的两条：消息免打扰、置顶聊天。
 * 这两条和会话列表长按那一项是同一份 ConvFlags，改完回到列表要跟着变。
 */
class ChatInfoActivity : EdgeBackActivity() {

    private lateinit var flags: ConvFlags
    private lateinit var rows: LinearLayout
    private lateinit var convId: String

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_chat_info)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        rows = findViewById(R.id.rows)
        convId = intent.getStringExtra("conv") ?: ""
        flags = ConvFlags(this)
        findViewById<TextView>(R.id.status).text =
            "这两条和会话列表长按那一项是同一份状态（存在本机）；后端没有对应字段。"
        render()
    }

    private fun render() {
        rows.removeAllViews()
        toggle("消息免打扰", flags.muted(convId)) {
            flags.toggleMuted(convId); render()
        }
        toggle("置顶聊天", flags.pinned(convId)) {
            flags.togglePinned(convId); render()
        }
        Rows.endGroup(rows)
    }

    /** 开关整行可点，不是只有那枚小药丸——药丸只有 40x24dp，按它太窄 */
    private fun toggle(label: String, on: Boolean, onClick: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_toggle, rows, false)
        v.findViewById<TextView>(R.id.label).text = label
        val track = v.findViewById<FrameLayout>(R.id.track)
        val thumb = v.findViewById<View>(R.id.thumb)
        track.setBackgroundResource(if (on) R.drawable.switch_track_on else R.drawable.switch_track_off)
        val lp = thumb.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (on) android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                     else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        lp.marginStart = if (on) 0 else (2 * resources.displayMetrics.density).toInt()
        lp.marginEnd = if (on) (2 * resources.displayMetrics.density).toInt() else 0
        thumb.layoutParams = lp
        v.findViewById<View>(R.id.tappable).setOnClickListener { onClick() }
        rows.addView(v)
    }
}
