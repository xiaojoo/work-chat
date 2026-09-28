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
    private lateinit var meAva: TextView
    private lateinit var meName: TextView
    private lateinit var meDot: View
    private lateinit var meState: TextView
    private var myName = ""
    /** 顶栏那颗状态点的数据源：本机到消息网关的那条连接（不是 /user/online，见 openGateway 的说明） */
    private var gw: Ws? = null

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
        meAva = v.findViewById(R.id.meAva)
        meName = v.findViewById(R.id.meName)
        meDot = v.findViewById(R.id.meDot)
        meState = v.findViewById(R.id.meState)
        v.findViewById<View>(R.id.plusBtn).setOnClickListener { showPlusMenu(it) }
        loadMe()
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

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        /* 从聊天信息页改了免打扰/置顶回来，这一页要跟着重排和换角标颜色——
           同一份状态的每个入口都得自己刷新，不重打接口，只重摊本地那份。
           但「多了一条会话」这种事摊不出来：转群新建的那条得重打接口才看得见，
           所以建完群那边会立 needsReload 这个牌子，这里认账并把它放倒。 */
        if (rows.isEmpty() || needsReload) { needsReload = false; load() } else filter()
        openGateway()
    }

    /** 离开这一页就把这条连接放掉：进聊天页时那边自己会开一条，别留两条 */
    override fun onPause() {
        super.onPause()
        closeGateway()
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
    /** 手指按下的屏幕坐标（rawX/rawY 含状态栏），长按菜单按它定位 */
    private var touchX = 0f
    private var touchY = 0f

    /** 顶栏身份块：昵称走 /api/user/{id}（后端 avatar 全是空串，所以和列表里同一套首字母方块）。 */
    private fun loadMe() {
        val ctx = requireContext()
        val base = Cfg.apiBase(ctx); val tk = Cfg.token(ctx); val id = Cfg.userId(ctx)
        if (base.isEmpty() || tk.isEmpty() || id.isEmpty()) { paintState("未登录", R.color.ink_dim2); return }
        thread(name = "me") {
            val r = runCatching { Api(base, tk).profile(id) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                myName = r.getOrNull()?.let { it.nickname.ifEmpty { it.username } } ?: ""
                meName.text = myName
                meAva.text = myName.take(1).uppercase()
            }
        }
    }

    /** 状态那一行量的是"这台手机 ↔ 消息网关"这条连接本身。
     *  不用 /api/user/online：它现在恒返回 []（那份在线表要 redis 支撑，这套进程没接上），
     *  拿它画就是永远一个"离线"，是假指示。 */
    private fun openGateway() {
        val ctx = requireContext()
        if (Cfg.token(ctx).isEmpty()) return
        closeGateway()
        paintState("连接中", R.color.warn)
        gw = Ws(Cfg.wsBase(ctx), Cfg.token(ctx), "chat-mobile-list",
            onFrame = { }, onState = { s -> activity?.runOnUiThread { if (isAdded) applyState(s) } }).also { it.open() }
    }

    private fun closeGateway() { gw?.close(); gw = null }

    /** Ws 报的是过程话术（"已连接"/"连接断开：xxx"/"连接关闭 1000"），翻成状态点那三个词 */
    private fun applyState(s: String) {
        when {
            s == "已连接" -> paintState("在线", R.color.ok)
            s.startsWith("连接断开") || s.startsWith("连接关闭") -> paintState("离线", R.color.ink_dim2)
            else -> paintState("连接中", R.color.warn)
        }
    }

    private fun paintState(text: String, colorRes: Int) {
        meState.text = text
        meDot.background.mutate().setTint(resources.getColor(colorRes, null))
    }

    /** 顶栏 ＋ 弹出来的小菜单。后端只有"群"没有"频道"，所以第二项画淡、不给点，
     *  和长按菜单"十项全摆八项灰"同一口径。菜单贴右上，右边缘留 8dp，不越屏幕。 */
    private fun showPlusMenu(anchor: View) {
        val items = listOf(
            Triple("创建群聊", R.drawable.ic_plus_circle, true),
            Triple("创建频道", R.drawable.ic_hash, false))
        val px = resources.displayMetrics.density
        val pop = ListPopupWindow(requireContext())
        pop.anchorView = anchor
        pop.setAdapter(object : ArrayAdapter<String>(requireContext(), R.layout.item_plus_menu, items.map { it.first }) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val row = cv ?: layoutInflater.inflate(R.layout.item_plus_menu, parent, false)
                row.findViewById<android.widget.ImageView>(R.id.ic).setImageResource(items[pos].second)
                row.findViewById<TextView>(R.id.text).text = items[pos].first
                row.alpha = if (items[pos].third) 1f else 0.4f
                return row
            }
            override fun areAllItemsEnabled() = false
            override fun isEnabled(pos: Int) = items[pos].third
        })
        pop.isModal = true
        pop.width = (190 * px).toInt()
        pop.setBackgroundDrawable(resources.getDrawable(R.drawable.menu_bg, null))
        pop.setOnItemClickListener { _, _, pos, _ ->
            pop.dismiss()
            if (items[pos].third) {
                startActivity(Intent(requireContext(), AddMembersActivity::class.java)
                    .putExtra("mode", "new").putExtra("mine", myName))
            }
        }
        val where = IntArray(2); anchor.getLocationOnScreen(where)
        pop.horizontalOffset =
            (resources.displayMetrics.widthPixels - 8 * px - pop.width - where[0]).toInt()
        pop.show()
    }

    /** 滑开露出定宽按钮：ItemTouchHelper 是"滑到底就提交"，停不住也点不了，所以这里自己接管。
     *  行程封顶在按钮总宽（左滑 224dp、右滑 84dp），松手过半停在全开、否则弹回。一次只开一行。 */
    private var openSlide: View? = null

    /** 收回去：只把这一行的内容层平移回 0 */
    private fun closeSlide(v: View?) {
        v?.animate()?.translationX(0f)?.setDuration(140)?.start()
        if (openSlide === v) openSlide = null
    }

    /** 未读那颗跟着这一行的状态走：有未读点下去是"标为已读"，没有是"标为未读" */
    private fun doRead(c: Api.Conversation) {
        if (unreadOf(c) > 0) { markRead(c); toast("已标为已读") }
        else { unreadOverride[c.id] = 1; refreshRow(c); toast("已标为未读") }
    }

    /** 行上的横向手势。行程封顶在按钮总宽（左滑 224dp = 70+84+70，右滑 84dp），
     *  所以按钮是定宽、能滑出去的距离也是定值；松手过半就停在全开，否则弹回。
     *  开着的时候点内容层只负责收回去，不会顺手把聊天打开。 */
    private fun bindSwipe(h: VH, c: Api.Conversation, nm: String) {
        val px = resources.displayMetrics.density
        val maxL = 224 * px
        val maxR = 84 * px
        val slop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        h.slide.translationX = 0f
        val read = if (unreadOf(c) > 0) "标为已读" else "标为未读"
        h.actPin.text = if (flags.pinned(c.id)) "取消置顶" else "置顶"
        h.actRead.text = read
        h.actReadR.text = read
        h.actPin.setOnClickListener { closeSlide(h.slide); flags.togglePinned(c.id); filter() }
        h.actRead.setOnClickListener { closeSlide(h.slide); doRead(c) }
        h.actReadR.setOnClickListener { closeSlide(h.slide); doRead(c) }
        h.actDel.setOnClickListener { closeSlide(h.slide); doDelete(c); toast("已删除「${c.name}」") }
        var x0 = 0f; var y0 = 0f; var t0 = 0f; var drag = false
        h.slide.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; t0 = v.translationX; drag = false
                    touchX = e.rawX; touchY = e.rawY
                    if (openSlide != null && openSlide !== v) closeSlide(openSlide)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    val dy = e.rawY - y0
                    if (!drag && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        drag = true
                        // 一旦判定是横拖，就把挂在那儿的长按掐掉：
                        // 不掐的话慢一点滑，行里的长按菜单会跟着弹出来盖在按钮上
                        v.cancelLongPress()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (drag) v.translationX = (t0 + dx).coerceIn(-maxL, maxR)
                }
                else -> {
                    if (drag) {
                        val t = v.translationX
                        val openL = t < -maxL * 0.35f
                        val openR = t > maxR * 0.35f
                        v.animate().translationX(if (openL) -maxL else if (openR) maxR else 0f)
                            .setDuration(140).start()
                        openSlide = if (openL || openR) v else null
                    }
                    drag = false
                }
            }
            false
        }
        h.slide.setOnClickListener {
            if (Math.abs(h.slide.translationX) > 0.5f) closeSlide(h.slide)
            else startActivity(Intent(requireContext(), ChatActivity::class.java)
                .putExtra("conv", c.id).putExtra("name", nm))
        }
        h.slide.setOnLongClickListener { showMenu(h.slide, c); true }
    }

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
        /* ListPopupWindow 没有 showAtLocation（那是 PopupWindow 的口），但它默认就贴在锚点
           行的左下沿——所以把偏移算成"手指点 - 行左下沿"，弹出来就是贴着手指那一点。
           右边放不下就左移、下面放不下就翻到手指上方（5 条 × 44dp 是估高，只用来决定翻不翻）。
           之前不写这两句偏移，弹框一直钉在行下方同一个位置，长按第二行它也从第一行底下冒出来。 */
        val px = resources.displayMetrics.density
        val where = IntArray(2); anchor.getLocationOnScreen(where)
        val scrW = resources.displayMetrics.widthPixels
        val scrH = resources.displayMetrics.heightPixels
        var offX = touchX - where[0]
        var offY = touchY - (where[1] + anchor.height)
        if (touchX + pop.width > scrW - 8 * px) offX = scrW - 8 * px - pop.width - where[0]
        val estH = items.size * 44 * px + 8 * px
        if (touchY + estH > scrH - 8 * px) offY = touchY - estH - (where[1] + anchor.height)
        pop.horizontalOffset = offX.toInt()
        pop.verticalOffset = offY.toInt()
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
            // 免打扰的铃铛：不管有没有未读都摆出来，不然"这条被静音了"只在点开设置里才看得见
            h.muted.visibility = if (flags.muted(c.id)) View.VISIBLE else View.GONE
            /* 最后一行不再画自己那根线：它下面没有另一行了，那根线（nb-line-soft 241,243,248）
               和页面底色（nb-bg-3 240,243,248）只差 1，画出来就是底边一个 3px 的台阶——
               线从 56dp 起头，所以左半截和右半截的白底收尾不在同一条 y 上 */
            h.line.visibility = if (i == rows.size - 1) View.GONE else View.VISIBLE
            bindSwipe(h, c, nm)
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ava: TextView = v.findViewById(R.id.ava)
        val name: TextView = v.findViewById(R.id.name)
        val last: TextView = v.findViewById(R.id.last)
        val time: TextView = v.findViewById(R.id.time)
        val unread: TextView = v.findViewById(R.id.unread)
        val muted: android.widget.ImageView = v.findViewById(R.id.muted)
        val line: View = v.findViewById(R.id.line)
        val slide: View = v.findViewById(R.id.slide)
        val actPin: TextView = v.findViewById(R.id.actPin)
        val actRead: TextView = v.findViewById(R.id.actRead)
        val actDel: TextView = v.findViewById(R.id.actDel)
        val actReadR: TextView = v.findViewById(R.id.actReadR)
    }

    companion object {
        /** 转群会新建一条会话，本地那份 all 里根本没有它，重摊也摊不出来——
         *  建完群那边把它立起来，这一页 onResume 看到就重打一次接口 */
        @Volatile var needsReload = false
    }
}
