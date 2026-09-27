package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 聊天里点对方头像进来的那一屏，对应桌面端「好友信息」弹框（Chat.vue 的 friendCard）。
 *
 * 字段走同一份白名单：部门 / 职务 / 邮箱 / 电话 / 个性签名，来自 GET :8081 /api/user/{id}；
 * 空值不列（桌面端也是 .filter(r => r.v)），五项都空就只留一行「对方资料都还没填」。
 * remark、location 后端恒返回空串且没有任何写入路径，两边都不摆。
 *
 * 「发消息」和桌面端 startChatWithFriend 同一条路径：先在会话列表里找和这个人的一对一会话，
 * 没有才 POST /api/conversation/create 建一条，然后把聊天页打开。
 */
class PersonActivity : EdgeBackActivity() {

    private lateinit var groups: LinearLayout
    private lateinit var status: TextView
    private lateinit var chatBtn: Button
    private var userId = ""
    private var shown = ""
    private var busy = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_person)
        groups = findViewById(R.id.groups)
        status = findViewById(R.id.status)
        chatBtn = findViewById(R.id.chat)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }

        userId = intent.getStringExtra("user") ?: ""
        shown = intent.getStringExtra("name") ?: ""
        findViewById<TextView>(R.id.name).text = shown
        findViewById<TextView>(R.id.ava).text = shown.take(1).uppercase()
        chatBtn.setOnClickListener { startChat() }
        load()
    }

    private fun load() {
        val base = Cfg.apiBase(this); val tk = Cfg.token(this)
        if (base.isEmpty() || tk.isEmpty() || userId.isEmpty()) { status.text = "还没登录"; return }
        status.text = "读取资料中…"
        thread(name = "person") {
            val res = runCatching { Api(base, tk).profile(userId) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess { p ->
                    shown = p.nickname.ifEmpty { p.username }.ifEmpty { shown }
                    findViewById<TextView>(R.id.name).text = shown
                    findViewById<TextView>(R.id.ava).text = shown.take(1).uppercase()
                    findViewById<TextView>(R.id.meta).text =
                        "@${p.username}" + if (p.id.isNotEmpty()) " · ID ${p.id}" else ""
                    renderFields(p)
                    status.text = "字段来自 GET :${Cfg.USER_PORT}/api/user/$userId；" +
                        "空值不列，和桌面端好友信息那份白名单一致（部门/职务/邮箱/电话/个性签名）"
                }.onFailure { status.text = "读取失败：${it.message}" }
            }
        }
    }

    /** 只列后端真有值的那几行——摆一行空值等于摆了个没做的控件 */
    private fun renderFields(p: Api.Profile) {
        groups.removeAllViews()
        val rows = listOf(
            "部门" to p.department, "职务" to p.position, "邮箱" to p.email,
            "电话" to p.phone, "个性签名" to p.bio)
        val filled = rows.filter { it.second.isNotEmpty() }
        if (filled.isEmpty()) {
            Rows.row(groups, "资料", "", hint = "对方资料都还没填", onClick = null)
        } else {
            filled.forEach { Rows.row(groups, it.first, it.second, onClick = null) }
        }
        Rows.endGroup(groups)
    }

    private fun startChat() {
        if (busy) return
        busy = true
        chatBtn.isEnabled = false
        val base = Cfg.apiBase(this); val tk = Cfg.token(this); val me = Cfg.userId(this)
        status.text = "找会话中…"
        thread(name = "chat") {
            val res = runCatching {
                val api = Api(base, tk)
                // 和桌面端一样：已有的一对一会话直接进去，别另建一条
                api.conversations(me).firstOrNull { it.type == 1 && it.targetId == userId }?.id
                    ?: api.createConversation(userId)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                busy = false
                chatBtn.isEnabled = true
                res.onSuccess { conv ->
                    startActivity(Intent(this, ChatActivity::class.java)
                        .putExtra("conv", conv).putExtra("name", shown))
                    finish()
                }.onFailure {
                    status.text = "发消息失败：${it.message}"
                    Toast.makeText(this, "发消息失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
