package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 聊天里点对方头像、或聊天信息页点某颗头像进来的那一屏。
 *
 * 版式照微信那张详细资料：头像在左，右边一列「标签：值」，下面一颗整行居中的「发消息」。
 * 值全部来自 GET :8081 /api/user/{id}，后端是空串的字段就不列那一行。
 * 微信那屏的 地区、备注、性别、朋友资料（改备注/标签/备忘/权限）、朋友圈、音视频通话——
 * 后端分别是恒空串且无写入路径、没有这个字段、没有这些接口，所以这一屏不摆，不照抄文案。
 *
 * 「发消息」和桌面端 startChatWithFriend 同一条路径：先在会话列表里找和这个人的一对一会话，
 * 没有才 POST /api/conversation/create 建一条，然后把聊天页打开。
 */
class PersonActivity : EdgeBackActivity() {

    private lateinit var lines: LinearLayout
    private lateinit var status: TextView
    private lateinit var chatRow: LinearLayout
    private var userId = ""
    private var shown = ""
    private var busy = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_person)
        lines = findViewById(R.id.lines)
        status = findViewById(R.id.status)
        chatRow = findViewById(R.id.chat)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }

        userId = intent.getStringExtra("user") ?: ""
        shown = intent.getStringExtra("name") ?: ""
        showName(shown)
        chatRow.setOnClickListener { startChat() }
        load()
    }

    private fun showName(nm: String) {
        findViewById<TextView>(R.id.name).text = nm
        findViewById<TextView>(R.id.ava).text = nm.take(1).uppercase()
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
                    val nm = p.nickname.ifEmpty { p.username }
                    shown = nm.ifEmpty { shown }
                    showName(nm.ifEmpty { shown })
                    renderFields(p)
                    status.text = "这几行来自 GET :${Cfg.USER_PORT}/api/user/$userId，后端是空串的字段就不列"
                }.onFailure { status.text = "读取失败：${it.message}" }
            }
        }
    }

    /** 只列后端真有值的那几行——摆一行空值等于摆了个没做的控件 */
    private fun renderFields(p: Api.Profile) {
        lines.removeAllViews()
        listOf(
            "账号" to p.username, "部门" to p.department, "职务" to p.position,
            "邮箱" to p.email, "电话" to p.phone, "个性签名" to p.bio,
            "用户 ID" to p.id)
            .filter { it.second.isNotEmpty() }
            .forEach { addLine("${it.first}：${it.second}") }
    }

    private fun addLine(text: String) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_person_line, lines, false)
        v.findViewById<TextView>(R.id.line).text = text
        lines.addView(v)
    }

    private fun startChat() {
        if (busy) return
        busy = true
        chatRow.isClickable = false
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
                chatRow.isClickable = true
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
