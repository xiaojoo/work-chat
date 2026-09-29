package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private lateinit var delRow: LinearLayout
    private var userId = ""
    private var shown = ""
    private var busy = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_person)
        lines = findViewById(R.id.lines)
        status = findViewById(R.id.status)
        chatRow = findViewById(R.id.chat)
        delRow = findViewById(R.id.delRow)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }

        userId = intent.getStringExtra("user") ?: ""
        shown = intent.getStringExtra("name") ?: ""
        showName(shown)
        chatRow.setOnClickListener { startChat() }
        delRow.setOnClickListener { confirmRemove() }
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
            /* 资料和"我们是不是好友"一起拉：后者决定「删除好友」那一行出不出——
               不是好友没得删，摆一颗点不动的灰行在这儿是骗人的 */
            val res = runCatching { Api(base, tk).let { it.profile(userId) to it.isFriend(userId) } }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess { (p, fr) ->
                    val nm = p.nickname.ifEmpty { p.username }
                    shown = nm.ifEmpty { shown }
                    showName(nm.ifEmpty { shown })
                    renderFields(p)
                    delRow.visibility = if (fr) View.VISIBLE else View.GONE
                    status.text = "这几行来自 GET :${Cfg.USER_PORT}/api/user/$userId，后端是空串的字段就不列"
                }.onFailure { status.text = "读取失败：${it.message}" }
            }
        }
    }

    /** 删的是"我和这个人还互为好友"这件事：后端两边各删一条（和直接互加对称），
     *  会话和它的聊天记录原样留着——措辞照真实行为写，不写成"聊天记录一起没" */
    private fun confirmRemove() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("删除好友 $shown")
            .setMessage("两边同时从各自的好友列表里去掉（DELETE /api/friend/remove/$userId）。" +
                "这条会话和它的聊天记录原样留着。")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("删除") { _, _ -> doRemove() }
            .show()
    }

    private fun doRemove() {
        busy = true
        val base = Cfg.apiBase(this); val tk = Cfg.token(this)
        thread(name = "rm-friend") {
            val res = runCatching { Api(base, tk).removeFriend(userId) }
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                res.onSuccess {
                    delRow.visibility = View.GONE
                    // 通讯录那页要重打 /friend/list，不然回到列表还看见这个人
                    ContactsFragment.needsReload = true
                    Toast.makeText(this, "已删除好友 $shown", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "删除失败：${it.message}", Toast.LENGTH_LONG).show()
                }
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
            val res = runCatching { Api(base, tk).privateConvWith(userId, me) }
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
