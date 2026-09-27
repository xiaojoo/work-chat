package com.chat.mobile

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.concurrent.thread

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

    /** 分组末尾那根线不要——微信的分组最后一行不画线，分组之间靠底色分开 */
    fun endGroup(parent: LinearLayout) {
        if (parent.childCount > 0) {
            parent.getChildAt(parent.childCount - 1)
                .findViewById<View>(R.id.line)?.visibility = View.GONE
        }
    }
}

/** 一行编辑框：标题、后端 PUT /user/profile 认的键、输入法、字数上限（后端 bio 截 200，这里先拦） */
data class Field(val label: String, val key: String, val ime: Int, val max: Int = 0)

object Fields {
    private const val TEXT = InputType.TYPE_CLASS_TEXT
    private const val DONE = EditorInfo.IME_ACTION_DONE

    val ALL = listOf(
        Field("名字", "nickname", TEXT or DONE),
        Field("部门", "department", TEXT or DONE),
        Field("职务", "position", TEXT or DONE),
        Field("邮箱", "email", TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or DONE),
        Field("电话", "phone", InputType.TYPE_CLASS_PHONE or DONE),
        Field("个性签名", "bio", TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or DONE, 200)
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

/** 改一处、PUT 一处、拿响应体刷界面；后端这个接口按 containsKey 逐条应用，所以只发被改的那个键 */
class ProfileEditor(private val host: androidx.appcompat.app.AppCompatActivity,
                    private val onSaved: (Api.Profile) -> Unit,
                    private val onBusy: (String) -> Unit) {

    fun edit(f: Field, current: String) {
        val input = EditText(host).apply {
            setText(current); setSelection(current.length)
            hint = f.label; inputType = f.ime
            setSingleLine(f.max == 0)
            if (f.max > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(f.max))
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(host)
            .setTitle("修改${f.label}")
            .setView(input)
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("保存") { d, _ ->
                val text = input.text.toString().trim()
                d.dismiss()
                onBusy("保存中…")
                thread(name = "profile-put") {
                    val res = runCatching {
                        Api(Cfg.apiBase(host), Cfg.token(host))
                            .updateProfile(f.key, text, Cfg.userId(host))
                    }
                    host.runOnUiThread {
                        res.onSuccess(onSaved)
                            .onFailure { onBusy("保存失败：${it.message}") }
                    }
                }
            }
            .show()
    }
}
