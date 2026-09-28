package com.chat.mobile

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 微信那种「左标题 / 右值 / 箭头」的行。我页和个人信息页都从这里出，
 * 免得同一套间距和颜色在两个类里各写一遍再慢慢漂移。
 */
object Rows {

    /** 微信的设置页不写分组标题，分组之间就是 8dp 的灰底缝。标题是我编的，宁可不要 */
    fun gap(parent: LinearLayout) {
        val ctx = parent.context
        val v = View(ctx)
        v.setBackgroundColor(ctx.getColor(R.color.page_bg))
        parent.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)))
    }

    private fun dp(ctx: android.content.Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /** 没有 onClick 的行就是纯展示：箭头直接 GONE，不摆一颗点了没反应的箭头 */
    fun row(parent: LinearLayout, label: String, value: String, hint: String = "",
            danger: Boolean = false, onClick: (() -> Unit)?): View {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_setting, parent, false)
        val labelView = v.findViewById<TextView>(R.id.label)
        labelView.text = label
        labelView.setTextColor(ctx.getColor(if (danger) R.color.danger else R.color.ink))
        val valView = v.findViewById<TextView>(R.id.value)
        val empty = value.isEmpty()
        valView.text = if (empty) hint else value
        valView.setTextColor(ctx.getColor(if (empty) R.color.ink_dim2 else R.color.ink_dim))
        val chev = v.findViewById<View>(R.id.chev)
        val tappable = v.findViewById<View>(R.id.tappable)
        if (onClick == null) {
            chev.visibility = View.GONE
            tappable.isClickable = false
            tappable.background = null
        } else {
            chev.visibility = if (danger) View.GONE else View.VISIBLE
            tappable.setOnClickListener { onClick() }
        }
        parent.addView(v)
        return v
    }

    /** 「左标题 / 右开关」的一行（复用聊天信息页那个自绘开关，不用系统 Switch）。
     *  enabled=false 时整行压淡、开关不给拨——摆出来但说清这颗现在是空的。 */
    fun toggle(parent: LinearLayout, label: String, on: Boolean, enabled: Boolean,
               labelSp: Float = 16f, onClick: (() -> Unit)?): View {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_toggle, parent, false)
        v.findViewById<TextView>(R.id.label).apply {
            text = label
            // item_toggle 是聊天信息页那两行开关共用的那份，字号不在共享件上改，调用方按自己那档设
            if (labelSp != 16f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, labelSp)
        }
        val track = v.findViewById<android.widget.FrameLayout>(R.id.track)
        track.setBackgroundResource(
            if (on) R.drawable.switch_track_on else R.drawable.switch_track_off)
        (track.getChildAt(0) as View).layoutParams =
            android.widget.FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply {
                gravity = if (on) android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                          else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                marginStart = dp(ctx, 2); marginEnd = dp(ctx, 2)
            }
        val tappable = v.findViewById<View>(R.id.tappable)
        if (onClick == null || !enabled) {
            v.alpha = 0.4f
            tappable.isClickable = false
            tappable.background = null
        } else tappable.setOnClickListener { onClick() }
        parent.addView(v)
        return v
    }

    /** 分组末尾那根线不要——微信的分组最后一行不画线，分组之间靠底色分开 */
    fun endGroup(parent: LinearLayout) {
        if (parent.childCount > 0) {
            parent.getChildAt(parent.childCount - 1)
                .findViewById<View>(R.id.line)?.visibility = View.GONE
        }
    }
}

/** 一行编辑框：标题、后端 PUT /user/profile 认的键、输入法、字数上限（后端 bio 截 200，这里先拦） */
data class Field(val label: String, val key: String, val ime: Int, val max: Int = 0, val hint: String = "")

object Fields {
    private const val TEXT = InputType.TYPE_CLASS_TEXT
    private const val DONE = EditorInfo.IME_ACTION_DONE
    /** 名字那句是照他给的那一屏抄的，其余几句只说这一页真做得到的事 */
    private const val SHOWN = "保存后显示在个人信息页；留空即不填。"

    val ALL = listOf(
        Field("名字", "nickname", TEXT or DONE, 0, "好名字可以让你的朋友更容易记住你。"),
        Field("部门", "department", TEXT or DONE, 0, SHOWN),
        Field("职务", "position", TEXT or DONE, 0, SHOWN),
        Field("邮箱", "email", TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or DONE, 0, SHOWN),
        Field("电话", "phone", InputType.TYPE_CLASS_PHONE or DONE, 0, SHOWN),
        Field("个性签名", "bio", TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or DONE, 200, "最多 200 字，超出的部分后端会截掉。")
    )

    fun value(p: Api.Profile, key: String): String = when (key) {
        "nickname" -> p.nickname
        "department" -> p.department
        "position" -> p.position
        "email" -> p.email
        "phone" -> p.phone
        else -> p.bio
    }
}
