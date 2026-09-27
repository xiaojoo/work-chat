package com.chat.mobile

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import kotlin.concurrent.thread

class ChatsFragment : Fragment() {

    private val rows = ArrayList<Api.Conversation>()
    private var all = ArrayList<Api.Conversation>()
    private var source = listOf<Api.Conversation>()
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var flags: ConvFlags

    /** 手动"标为未读/已读"的结果只活在这一份内存表里：服务端没有这个字段，
     *  下拉刷新会把它清掉（桌面端刷新同样丢，行为对齐，不自作主张持久化） */
    private val unreadOverride = HashMap<String, Int>()

    private fun unreadOf(c: Api.Conversation): Int = unreadOverride[c.id] ?: c.unread

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
        flags = ConvFlags(requireContext())
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
        attachSwipes()
    }

    /** 左滑=删除，右滑=标为已读/标为未读；两向都不弹框——滑到底就直接做。
     *  滑开的那块空档刷成动作色、下沿写上这一滑要干什么（删除 / 已读 / 未读）：
     *  松手之前就该看清动作目的，不用等做完再靠一句 toast 回想。
     *  微信是先露两个按钮再点一次，这里少一层"藏在行后面的按钮"，也就少一种点了看不见的命中区 */
    private fun attachSwipes() {
        val px = resources.displayMetrics.density
        val danger = 0xFFD94860.toInt()      // 令牌 danger
        val brand = 0xFF2B6BE8.toInt()       // 令牌 brand
        val bg = android.graphics.Paint().apply { isAntiAlias = true }
        val ink = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 12f * px
            color = 0xFFFFFFFF.toInt()
        }
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onChildDraw(c: Canvas, rv: RecyclerView, h: RecyclerView.ViewHolder,
                                     dX: Float, dY: Float, state: Int, active: Boolean) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    val row = h.itemView
                    val del = dX < 0f
                    // 空出来的那一条：往左滑露在右边，往右滑露在左边
                    val l = if (del) row.right + dX else 0f
                    val r = if (del) row.right.toFloat() else dX
                    bg.color = if (del) danger else brand
                    c.drawRect(l, row.top.toFloat(), r, row.bottom.toFloat(), bg)
                    val label = if (del) "删除" else swipeLabel(h.bindingAdapterPosition)
                    ink.textAlign = if (del) Paint.Align.RIGHT else Paint.Align.LEFT
                    // 字贴着空档的外侧、下沿留 10dp；空档不够宽时被 clipRect 裁掉，不压到行文字上
                    val tx = if (del) r - 16 * px else l + 16 * px
                    val ty = row.bottom - 10 * px - ink.fontMetrics.bottom
                    c.save()
                    c.clipRect(l, row.top.toFloat(), r, row.bottom.toFloat())
                    c.drawText(label, tx, ty, ink)
                    c.restore()
                }
                super.onChildDraw(c, rv, h, dX, dY, state, active)
            }

            override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= rows.size) return
                val c = rows[pos]
                list.adapter?.notifyItemChanged(pos)
                if (dir == ItemTouchHelper.LEFT) { doDelete(c); toast("已删除「${c.name}」") }
                else if (unreadOf(c) > 0) { markRead(c); toast("已标为已读") }
                else { unreadOverride[c.id] = 1; refreshRow(c); toast("已标为未读") }
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(list)
    }

    /** 右滑这颗动作的名字跟着这一行的状态走：有未读=这一滑标已读，没未读=这一滑标未读 */
    private fun swipeLabel(pos: Int): String =
        rows.getOrNull(pos)?.let { if (unreadOf(it) > 0) "已读" else "未读" } ?: "未读"

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        /* 从聊天信息页改了免打扰/置顶回来，这一页要跟着重排和换角标颜色——
           同一份状态的每个入口都得自己刷新，不重打接口，只重摊本地那份。
           但「多了一条会话」这种事摊不出来：转群新建的那条得重打接口才看得见，
           所以建完群那边会立 needsReload 这个牌子，这里认账并把它放倒。 */
        if (rows.isEmpty() || needsReload) { needsReload = false; load() } else filter()
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val visible = all.filter { !flags.hidden(it.id, it.lastMessageTime) }
            .let { if (q.isEmpty()) it else it.filter { c -> c.name.lowercase().contains(q) } }
        // 置顶只改顺序，不改数据；和桌面端一样，稳定排序：置顶组在前，组内保持原序
        source = visible.filter { flags.pinned(it.id) } + visible.filter { !flags.pinned(it.id) }
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
        val unread = source.sumOf { unreadOf(it) }
        val hidden = flags.hiddenCount()
        status.text = "${source.size} 个会话 · 未读合计 $unread · 已显示 ${rows.size}（分批渲染，接口无分页参数）" +
            if (hidden > 0) " · 已隐藏 $hidden，来新消息会自动回来" else ""
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
                    unreadOverride.clear(); all = ArrayList(got); filter()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    refresh.isRefreshing = false
                    if (isAdded) status.text = "拉取失败：${e.message}"
                }
            }
        }
    }

    // ===== 长按：对应桌面端会话行的右键菜单 =====
    // 桌面端那六项里，独立窗口是桌面壳的能力、手机上没有；置顶/免打扰/不显示/标为未读后端没有字段，
    // 存在本机；只有删除真打接口。所以这一排里没有一项是点了不动的。
    private fun showMenu(anchor: View, c: Api.Conversation) {
        val items = listOf(
            if (flags.pinned(c.id)) "取消置顶" else "置顶该聊天",
            if (unreadOf(c) > 0) "标为已读" else "标为未读",
            if (flags.muted(c.id)) "关闭消息免打扰" else "消息免打扰",
            "不显示",
            "删除该聊天"
        )
        val pop = ListPopupWindow(requireContext())
        pop.anchorView = anchor
        pop.setAdapter(ArrayAdapter(requireContext(), R.layout.item_menu, R.id.text, items))
        pop.isModal = true
        pop.width = (190 * resources.displayMetrics.density).toInt()
        pop.setBackgroundDrawable(resources.getDrawable(R.drawable.menu_bg, null))
        pop.setOnItemClickListener { _, _, pos, _ ->
            pop.dismiss()
            run(items[pos], c)
        }
        pop.show()
    }

    private fun run(item: String, c: Api.Conversation) {
        when (item) {
            "置顶该聊天", "取消置顶" -> { flags.togglePinned(c.id); filter() }
            "消息免打扰", "关闭消息免打扰" -> { flags.toggleMuted(c.id); list.adapter?.notifyDataSetChanged() }
            "标为未读" -> { unreadOverride[c.id] = unreadOf(c) + 1; refreshRow(c) }
            "标为已读" -> { markRead(c) }
            "不显示" -> { flags.hide(c.id, c.lastMessageTime); filter() }
            "删除该聊天" -> { confirmDelete(c) }
        }
    }

    /** 标为已读走真接口：桌面端那条只是本地清 0，手机上有 clearUnread 可用，就顺手做彻底 */
    private fun markRead(c: Api.Conversation) {
        unreadOverride[c.id] = 0
        refreshRow(c)
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext())
        val uid = Cfg.userId(requireContext())
        thread(name = "mark-read") {
            runCatching { Api(base, tk).clearUnread(c.id, uid) }
                .onFailure { activity?.runOnUiThread { if (isAdded) renderMeta() } }
        }
    }

    private fun refreshRow(c: Api.Conversation) {
        val i = rows.indexOfFirst { it.id == c.id }
        if (i >= 0) list.adapter?.notifyItemChanged(i)
        renderMeta()
    }

    private fun confirmDelete(c: Api.Conversation) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除该聊天")
            .setMessage("会删掉服务器上这条会话（DELETE /api/conversation），聊天记录本身不动。")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("删除") { _, _ -> doDelete(c) }
            .show()
    }

    private fun doDelete(c: Api.Conversation) {
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext())
        val uid = Cfg.userId(requireContext())
        status.text = "删除中…"
        thread(name = "conv-del") {
            val res = runCatching { Api(base, tk).deleteConversation(c.id, uid) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                res.onSuccess {
                    flags.forget(c.id); unreadOverride.remove(c.id)
                    all.removeAll { it.id == c.id }; filter()
                }.onFailure { status.text = "删除失败：${it.message}" }
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
            val u = unreadOf(c)
            h.ava.text = nm.take(1).uppercase()
            h.name.text = nm
            h.last.text = c.lastMessage
            h.time.text = Times.conv(c.lastMessageTime)
            h.unread.text = if (u > 99) "99+" else u.toString()
            h.unread.visibility = if (u > 0) View.VISIBLE else View.GONE
            h.unread.setBackgroundResource(
                if (flags.muted(c.id)) R.drawable.unread_badge_muted else R.drawable.unread_badge)
            /* 最后一行不再画自己那根线：它下面没有另一行了，那根线（nb-line-soft 241,243,248）
               和页面底色（nb-bg-3 240,243,248）只差 1，画出来就是底边一个 3px 的台阶——
               线从 56dp 起头，所以左半截和右半截的白底收尾不在同一条 y 上 */
            h.line.visibility = if (i == rows.size - 1) View.GONE else View.VISIBLE
            h.itemView.setOnClickListener {
                startActivity(Intent(requireContext(), ChatActivity::class.java)
                    .putExtra("conv", c.id).putExtra("name", nm))
            }
            h.itemView.setOnLongClickListener { showMenu(it, c); true }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ava: TextView = v.findViewById(R.id.ava)
        val name: TextView = v.findViewById(R.id.name)
        val last: TextView = v.findViewById(R.id.last)
        val time: TextView = v.findViewById(R.id.time)
        val unread: TextView = v.findViewById(R.id.unread)
        val line: View = v.findViewById(R.id.line)
    }

    companion object {
        /** 转群会新建一条会话，本地那份 all 里根本没有它，重摊也摊不出来——
         *  建完群那边把它立起来，这一页 onResume 看到就重打一次接口 */
        @Volatile var needsReload = false
    }
}
