package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.concurrent.thread

class ContactsFragment : Fragment() {

    private sealed interface Line
    private data class Head(val letter: String) : Line
    private data class Person(val f: Api.Friend) : Line
    /** 顶部那一档「群聊」：微信是"行 → 点进去一列表"，不是把群摊在通讯录里 */
    private data class Entry(val count: Int) : Line

    private val lines = ArrayList<Line>()
    private var allLines = ArrayList<Line>()
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var rail: LinearLayout
    private lateinit var sticky: TextView
    private lateinit var bubble: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private var friends = listOf<Api.Friend>()
    private var groupConvs = listOf<Api.Conversation>()

    /** 索引条铺满 26+#，不是只摆"有人的那几个"：微信那一条是常驻全字母，
     *  只有 1 个联系人时也能看出这是一条 A-Z，而不是一排散字 */
    private val LETTERS = ('A'..'Z').map { it.toString() } + "#"

    /** 和会话列表同一条：/friend/list 没有 limit/offset，只能分批渲染 */
    private val PAGE = 30
    private var shownHeads = 0
    private var shownPeople = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_contacts, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        list = v.findViewById(R.id.list)
        status = v.findViewById(R.id.status)
        rail = v.findViewById(R.id.rail)
        sticky = v.findViewById(R.id.sticky)
        bubble = v.findViewById(R.id.bubble)
        refresh = v.findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        refresh.setOnRefreshListener { load() }
        refresh.setColorSchemeColors(requireContext().getColor(R.color.brand))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    if (lm.findLastVisibleItemPosition() >= lines.size - 5) loadMore()
                }
                renderSticky()
            }
        })
        buildRail()
        rail.setOnTouchListener { _, e -> onRailTouch(e) }
        /* scrollToPositionWithOffset 不发 onScrolled，只挂滚动监听的话，
           点索引条跳完组头吸不住，要等下一次手滑才更新——挂在布局上两条路径都覆盖 */
        list.viewTreeObserver.addOnGlobalLayoutListener { renderSticky() }
    }

    /** 手指顺着索引条滑：按下和拖动都算，抬起收掉回显。列表是分批渲染的，
     *  所以先把目标那一组摊出来再跳，不然会跳到没有渲染的位置上 */
    private fun onRailTouch(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                val h = rail.height - rail.paddingTop - rail.paddingBottom
                if (h <= 0 || groups.isEmpty()) return true
                val i = ((e.y - rail.paddingTop) / h * LETTERS.size).toInt().coerceIn(0, LETTERS.size - 1)
                val letter = LETTERS[i]
                bubble.text = letter
                bubble.visibility = View.VISIBLE
                jumpNearest(letter)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                bubble.visibility = View.GONE
        }
        return true
    }

    private fun jumpNearest(target: String) {
        val keys = groups.keys.toList()
        val ti = rank(target)
        val hit = keys.indexOfFirst { rank(it) >= ti }
        val letter = keys[if (hit >= 0) hit else keys.size - 1]
        var guard = 0
        while (lines.indexOfFirst { it is Head && it.letter == letter } < 0 &&
            lines.size < allLines.size && guard++ < 40) loadMore()
        jumpTo(letter)
    }

    private fun rank(letter: String): Int = LETTERS.indexOf(letter).let { if (it < 0) LETTERS.size else it }

    override fun onResume() { super.onResume(); if (friends.isEmpty()) load() }

    private fun load() {
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext())
        val uid = Cfg.userId(requireContext())
        if (base.isEmpty() || tk.isEmpty()) { status.text = "还没登录"; refresh.isRefreshing = false; return }
        refresh.isRefreshing = true
        status.text = "加载中…"
        thread(name = "friends") {
            /* 群聊没有"我的群列表"这个口，但 /conversation/list 里 type=2 的就是我加入的群，
               桌面端的群也是从这儿来的，所以不另开服务 */
            val res = runCatching {
                Api(base, tk).friends() to
                    Api(base, tk).conversations(uid).filter { it.type == 2 }
            }
            activity?.runOnUiThread {
                refresh.isRefreshing = false
                if (!isAdded) return@runOnUiThread
                res.onSuccess { (f, g) -> friends = f; groupConvs = g; rebuild() }
                    .onFailure { status.text = "拉取失败：${it.message}" }
            }
        }
    }

    /** 按拼音首字母分组，组内也按拼音排。
     *  组的先后不能跟着比较器的自然序——JVM 那套 Collator 会把拉丁名排到中文名后面，
     *  实测 admin 落到了 Z 之后，而索引条是 A-Z 的，组序必须跟着 A-Z 走，所以按 rank 重排一遍 */
    private fun rebuild() {
        val byLetter = LinkedHashMap<String, ArrayList<Api.Friend>>()
        for (f in friends) byLetter.getOrPut(Pinyin.initial(name(f))) { ArrayList() }.add(f)
        groups = LinkedHashMap()
        for (k in byLetter.keys.sortedBy { rank(it) }) {
            groups[k] = ArrayList(byLetter[k]!!.sortedWith { a, b -> Pinyin.compare(name(a), name(b)) })
        }
        lines.clear(); shownHeads = 0; shownPeople = 0
        allLines = ArrayList()
        if (groupConvs.isNotEmpty()) allLines.add(Entry(groupConvs.size))
        for ((letter, fs) in groups) {
            allLines.add(Head(letter))
            fs.forEach { allLines.add(Person(it)) }
        }
        emit(PAGE)
        list.adapter?.notifyDataSetChanged()
        buildRail()
        renderSticky()
        renderMeta()
    }

    private var groups = LinkedHashMap<String, ArrayList<Api.Friend>>()

    /** 一次摊出前 n 行。整表 allLines 一次算好，这里只截前缀——
     *  之前那种"往上追加"会把摊过的组再摊一遍，联系人一多整列表成对重复 */
    private fun emit(n: Int) {
        lines.clear()
        lines.addAll(allLines.take(n))
        shownHeads = lines.count { it is Head }
        shownPeople = lines.count { it is Person }
    }

    private fun loadMore() {
        if (lines.size >= allLines.size) return
        val from = lines.size
        emit(lines.size + PAGE)
        list.adapter?.notifyItemRangeInserted(from, lines.size - from)
        renderMeta()
    }

    private fun renderMeta() {
        val total = friends.size
        status.text = "$total 位联系人 · ${groupConvs.size} 个群聊 · " +
            "已显示 $shownPeople/$total（$shownHeads 组）· 接口无分页参数"
    }

    private fun name(f: Api.Friend) = f.nickname.ifEmpty { f.username }

    /** 点一个联系人 = 进和这条单聊。先按 targetId 找已有会话，没有才建一条
     *  （和桌面端 startChatWithFriend、好友信息页那颗「发消息」同一句判断）。
     *  原来这里只弹一句"会话在微信页里开"——那是个点了没去处的控件。 */
    private var busyChat = false
    private fun openChat(userId: String, nm: String) {
        val ctx = requireContext()
        val base = Cfg.apiBase(ctx); val tk = Cfg.token(ctx); val me = Cfg.userId(ctx)
        if (base.isEmpty() || tk.isEmpty() || userId.isEmpty()) { say("还没登录"); return }
        if (busyChat) return
        busyChat = true
        status.text = "打开和 $nm 的会话…"
        thread(name = "open-chat") {
            val res = runCatching { Api(base, tk).privateConvWith(userId, me) }
            activity?.runOnUiThread {
                busyChat = false
                if (!isAdded) return@runOnUiThread
                res.onSuccess { id ->
                    status.text = ""
                    startActivity(Intent(ctx, ChatActivity::class.java)
                        .putExtra("conv", id).putExtra("name", nm))
                }.onFailure {
                    status.text = "打不开和 $nm 的会话"; say("打不开：${it.message}")
                }
            }
        }
    }

    private fun say(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 组头自己带一根下边框，所以它上面那一行不能再画自己的线——否则两条线夹着一条灰带，看着像双线。
     *  最后一行下面也没有行了，同样不画：那根线（241,243,248）和页面底色（240,243,248）只差 1，
     *  画出来就是列表底边一个台阶——线从文字起头，左半截和右半截收尾不在同一条 y 上 */
    private fun lineBefore(next: Any?): Int = when {
        next == null || next is Head -> View.GONE
        else -> View.VISIBLE
    }

    /** 分组标题吸顶：只有当这一组真正的组头完全滚出上沿之后才钉住它。
     *  原来用"第一个可见项是不是组头"判断，组头露 1 个像素也算露着，结果滚到一半谁都不钉 */
    private fun renderSticky() {
        if (!::sticky.isInitialized || lines.isEmpty()) return
        val lm = list.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) { sticky.visibility = View.GONE; return }
        var letter: String? = null
        for (j in minOf(first, lines.size - 1) downTo 0) {
            val ln = lines[j]
            if (ln is Head) { letter = ln.letter; break }
        }
        if (letter == null) { sticky.visibility = View.GONE; return }
        val headIdx = lines.indexOfFirst { it is Head && it.letter == letter }
        val headView = if (headIdx >= 0) lm.findViewByPosition(headIdx) else null
        if (headView != null && headView.bottom > 0) { sticky.visibility = View.GONE; return }
        sticky.text = letter
        sticky.visibility = View.VISIBLE
    }

    /** 整列 26+# 常驻；这一组没人的字母画淡一点，但照样能点——点了跳到它后面最近的一组，
     *  和系统快速滚动条一个行为，不摆一颗按了什么都不发生的字母 */
    private fun buildRail() {
        rail.removeAllViews()
        val dm = resources.displayMetrics.density
        val itemH = (18 * dm).toInt()
        for (l in LETTERS) {
            val tv = TextView(requireContext())
            tv.text = l
            tv.textSize = 13f
            tv.gravity = android.view.Gravity.CENTER
            tv.setTextColor(requireContext().getColor(R.color.ink_dim))
            tv.alpha = if (groups.containsKey(l)) 1f else 0.32f
            rail.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemH))
        }
    }

    private fun jumpTo(letter: String) {
        val i = lines.indexOfFirst { it is Head && it.letter == letter }
        if (i >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(p: Int) = when (lines[p]) {
            is Head -> 0; is Entry -> 2; else -> 1
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            return when (t) {
                0 -> HeadVH(inf.inflate(R.layout.item_contact_head, p, false))
                2 -> EntryVH(inf.inflate(R.layout.item_entry, p, false))
                else -> PersonVH(inf.inflate(R.layout.item_contact, p, false))
            }
        }

        override fun getItemCount() = lines.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            when (val ln = lines[i]) {
                is Head -> (h as HeadVH).letter.text = ln.letter
                is Entry -> {
                    val v = h as EntryVH
                    v.label.text = "群聊"
                    v.count.text = ln.count.toString()
                    v.line.visibility = lineBefore(lines.getOrNull(i + 1))
                    v.itemView.setOnClickListener {
                        startActivity(Intent(requireContext(), GroupListActivity::class.java))
                    }
                }
                is Person -> {
                    val v = h as PersonVH
                    val nm = name(ln.f)
                    v.ava.text = nm.take(1).uppercase()
                    v.name.text = nm
                    v.sub.text = "@${ln.f.username}"
                    v.line.visibility = lineBefore(lines.getOrNull(i + 1))
                    v.itemView.setOnClickListener { openChat(ln.f.id, nm) }
                }
                /** 群聊这一档：桌面端的群本来就在会话列表里，这里给的是同一个入口，
                 *  点了进那一页的列表，不是又造一份数据 */
            }
        }
    }

    private class HeadVH(v: View) : RecyclerView.ViewHolder(v) {
        val letter: TextView = v.findViewById(R.id.letter)
    }

    private class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.label)
        val count: TextView = v.findViewById(R.id.value)
        val line: View = v.findViewById(R.id.line)
    }

    private class PersonVH(v: View) : RecyclerView.ViewHolder(v) {
        val ava: TextView = v.findViewById(R.id.ava)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val line: View = v.findViewById(R.id.line)
    }
}
