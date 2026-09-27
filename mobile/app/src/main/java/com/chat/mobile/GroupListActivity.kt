package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/**
 * 通讯录「群聊」那一档点进来的一页。
 * 数据还是 /conversation/list 里 type=2 的那些——和会话列表、和桌面端读的是同一份，
 * 这里只是换个入口，不是又存了一份群。
 */
class GroupListActivity : EdgeBackActivity() {

    private var groups = listOf<Api.Conversation>()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_group_list)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        val list = findViewById<RecyclerView>(R.id.list)
        val status = findViewById<TextView>(R.id.status)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
                LayoutInflater.from(p.context).inflate(R.layout.item_contact, p, false))
            override fun getItemCount() = groups.size
            override fun onBindViewHolder(h: VH, i: Int) {
                val g = groups[i]
                val nm = g.name.ifEmpty { "群聊 ${g.id}" }
                h.ava.text = nm.take(1).uppercase()
                h.name.text = nm
                h.sub.text = if (g.unread > 0) "${g.unread} 条未读" else "群聊"
                h.itemView.setOnClickListener {
                    startActivity(Intent(this@GroupListActivity, ChatActivity::class.java)
                        .putExtra("conv", g.id).putExtra("name", nm))
                }
            }
        }

        val base = Cfg.apiBase(this); val tk = Cfg.token(this); val uid = Cfg.userId(this)
        if (base.isEmpty() || tk.isEmpty() || uid.isEmpty()) { status.text = "还没登录"; return }
        status.text = "加载中…"
        thread(name = "groups") {
            val res = runCatching { Api(base, tk).conversations(uid).filter { it.type == 2 } }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess {
                    groups = it; list.adapter?.notifyDataSetChanged()
                    status.text = "${it.size} 个群 · 来自 GET /api/conversation/list（type=2）"
                }.onFailure { status.text = "拉取失败：${it.message}" }
            }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ava: TextView = v.findViewById(R.id.ava)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
    }
}
