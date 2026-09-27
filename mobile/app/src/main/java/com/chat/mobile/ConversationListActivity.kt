package com.chat.mobile

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class ConversationListActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private val rows = ArrayList<Api.Conversation>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)

        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()

        findViewById<Button>(R.id.reload).setOnClickListener { load() }
        load()
    }

    private fun load() {
        val base = Cfg.apiBase(this)
        val token = Cfg.token(this)
        val uid = Cfg.userId(this)
        if (base.isEmpty() || token.isEmpty() || uid.isEmpty()) {
            status.text = "没有会话：还没登录（缺地址/令牌/userId）"
            return
        }
        status.text = "加载中…"
        thread(name = "conversations") {
            try {
                val got = Api(base, token).conversations(uid)
                runOnUiThread {
                    rows.clear(); rows.addAll(got)
                    list.adapter?.notifyDataSetChanged()
                    val unread = rows.sumOf { it.unread }
                    status.text = "${rows.size} 个会话 · 未读合计 $unread"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "拉取失败：" + e.message }
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_conversation, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val c = rows[i]
            h.name.text = c.name
            h.last.text = c.lastMessage
            // 未读数在这里必须是可见的：手机上是"看+回+收"，看不到未读这个端就没意义
            h.unread.text = if (c.unread > 0) (if (c.unread > 99) "99+" else c.unread.toString()) else ""
            h.unread.visibility = if (c.unread > 0) android.view.View.VISIBLE else android.view.View.GONE
            h.itemView.setOnClickListener {
                startActivity(android.content.Intent(this@ConversationListActivity, ChatActivity::class.java)
                    .putExtra("conv", c.id).putExtra("name", c.name))
            }
        }
    }

    private class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val last: TextView = v.findViewById(R.id.last)
        val unread: TextView = v.findViewById(R.id.unread)
    }
}
