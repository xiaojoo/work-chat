package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.concurrent.thread

class ChatsFragment : Fragment() {

    private val rows = ArrayList<Api.Conversation>()
    private var all = ArrayList<Api.Conversation>()
    private var source = listOf<Api.Conversation>()
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var refresh: SwipeRefreshLayout

    /** 接口没有 limit/offset（/conversation/list 一次给全量），所以这里只能分批渲染，
     *  不是服务端分页——界面上那句"已显示 x/y"就是为了让这个区别看得见 */
    private val PAGE = 20

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_chats, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        list = v.findViewById(R.id.list)
        status = v.findViewById(R.id.status)
        search = v.findViewById(R.id.search)
        refresh = v.findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = filter()
            override fun afterTextChanged(t: android.text.Editable?) {}
        })
        refresh.setOnRefreshListener { load() }
        refresh.setColorSchemeColors(0xFF2B6BE8.toInt())
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= rows.size - 5) loadMore()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (rows.isEmpty()) load()
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        source = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
        rows.clear()
        rows.addAll(source.take(PAGE))
        list.adapter?.notifyDataSetChanged()
        renderMeta()
    }

    private fun loadMore() {
        if (rows.size >= source.size) return
        val from = rows.size
        val take = source.subList(from, minOf(from + PAGE, source.size))
        rows.addAll(take)
        list.adapter?.notifyItemRangeInserted(from, take.size)
        renderMeta()
    }

    private fun renderMeta() {
        val unread = source.sumOf { it.unread }
        status.text = "${source.size} 个会话 · 未读合计 $unread · 已显示 ${rows.size}（分批渲染，接口无分页参数）"
    }

    private fun load() {
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext()); val uid = Cfg.userId(requireContext())
        if (base.isEmpty() || tk.isEmpty() || uid.isEmpty()) { status.text = "还没登录"; refresh.isRefreshing = false; return }
        refresh.isRefreshing = true
        status.text = "加载中…"
        thread(name = "chats") {
            try {
                val got = Api(base, tk).conversations(uid)
                activity?.runOnUiThread {
                    refresh.isRefreshing = false
                    if (!isAdded) return@runOnUiThread
                    all = ArrayList(got); filter()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    refresh.isRefreshing = false
                    if (isAdded) status.text = "拉取失败：${e.message}"
                }
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat, p, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val c = rows[i]
            val nm = c.name.ifEmpty { "会话 ${c.id}" }
            h.ava.text = nm.take(1).uppercase()
            h.name.text = nm
            h.last.text = c.lastMessage
            h.time.text = Times.conv(c.lastMessageTime)
            h.unread.text = if (c.unread > 99) "99+" else c.unread.toString()
            h.unread.visibility = if (c.unread > 0) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener {
                startActivity(Intent(requireContext(), ChatActivity::class.java)
                    .putExtra("conv", c.id).putExtra("name", nm))
            }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ava: TextView = v.findViewById(R.id.ava)
        val name: TextView = v.findViewById(R.id.name)
        val last: TextView = v.findViewById(R.id.last)
        val time: TextView = v.findViewById(R.id.time)
        val unread: TextView = v.findViewById(R.id.unread)
    }
}
