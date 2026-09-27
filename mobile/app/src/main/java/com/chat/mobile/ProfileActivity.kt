package com.chat.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * 个人信息页：微信那张竖排表（头像 / 名字 / 账号 / 部门…），左标题右值带箭头。
 * 箭头只给 PUT /api/user/profile 真认的键；账号和 ID 后端没有写入路径，就不摆一颗点不动的箭头。
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var prof: Api.Profile? = null
    private lateinit var editor: ProfileEditor

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_profile)
        rows = findViewById(R.id.groups)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }

        editor = ProfileEditor(
            host = this,
            onSaved = { p -> prof = p; render(); status.text = "已保存，来源 PUT /api/user/profile" },
            onBusy = { msg -> status.text = msg }
        )
        load()
    }

    private fun load() {
        val base = Cfg.apiBase(this); val tk = Cfg.token(this); val uid = Cfg.userId(this)
        if (base.isEmpty() || tk.isEmpty() || uid.isEmpty()) { status.text = "还没登录"; return }
        status.text = "读取资料中…"
        thread(name = "profile-get") {
            val res = runCatching { Api(base, tk).profile(uid) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess { prof = it; render(); status.text = "资料来自 GET /api/user/{id}" }
                    .onFailure { status.text = "读取失败：${it.message}" }
            }
        }
    }

    private fun render() {
        val p = prof ?: return
        rows.removeAllViews()

        val av = LayoutInflater.from(this).inflate(R.layout.item_avatar, rows, false)
        av.findViewById<TextView>(R.id.ava).text =
            (p.nickname.ifEmpty { p.username }).take(1).uppercase()
        rows.addView(av)

        fun editable(key: String) {
            val f = Fields.ALL.first { it.key == key }
            Rows.row(rows, f.label, Fields.value(p, key), "未填写") {
                editor.edit(f, Fields.value(p, key))
            }
        }
        editable("nickname")
        Rows.row(rows, "账号", "@${p.username}", onClick = null)
        Rows.row(rows, "用户 ID", p.id, onClick = null)
        listOf("department", "position", "email", "phone", "bio").forEach { editable(it) }
        Rows.endGroup(rows)
    }
}
