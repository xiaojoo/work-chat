package com.chat.mobile

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

    private val lines = ArrayList<Line>()
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var rail: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout
    private var friends = listOf<Api.Friend>()

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
        refresh = v.findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
        refresh.setOnRefreshListener { load() }
        refresh.setColorSchemeColors(0xFF2B6BE8.toInt())
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= lines.size - 5) loadMore()
            }
        })
    }

    override fun onResume() { super.onResume(); if (friends.isEmpty()) load() }

    private fun load() {
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext())
        if (base.isEmpty() || tk.isEmpty()) { status.text = "还没登录"; refresh.isRefreshing = false; return }
        refresh.isRefreshing = true
        status.text = "加载中…"
        thread(name = "friends") {
            try {
                val got = Api(base, tk).friends()
                activity?.runOnUiThread {
                    refresh.isRefreshing = false
                    if (isAdded) { friends = got; rebuild() }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    refresh.isRefreshing = false
                    if (isAdded) status.text = "拉取失败：${e.message}"
                }
            }
        }
    }

    /** 按拼音首字母分组，组内也按拼音排 */
    private fun rebuild() {
        groups = LinkedHashMap()
        for (f in friends.sortedWith { a, b -> Pinyin.compare(name(a), name(b)) }) {
            groups.getOrPut(Pinyin.initial(name(f))) { ArrayList() }.add(f)
        }
        lines.clear(); shownHeads = 0; shownPeople = 0
        appendUpTo(PAGE)
        list.adapter?.notifyDataSetChanged()
        buildRail(groups.keys.toList())
        renderMeta()
    }

    private var groups = LinkedHashMap<String, ArrayList<Api.Friend>>()

    /** 一行行摊平成 [组标题, 人, 人, 组标题, ...]，一次只摊 PAGE 行 */
    private fun appendUpTo(n: Int) {
        var emitted = 0
        outer@ for ((letter, fs) in groups) {
            if (emitted >= n) break
            lines.add(Head(letter)); emitted++
            for (f in fs) {
                if (emitted >= n) break@outer
                lines.add(Person(f)); emitted++
            }
        }
        shownHeads = lines.count { it is Head }
        shownPeople = lines.count { it is Person }
    }

    private fun loadMore() {
        if (shownPeople >= friends.size) return
        val from = lines.size
        appendUpTo(lines.size + PAGE)
        if (lines.size > from) list.adapter?.notifyItemRangeInserted(from, lines.size - from)
        renderMeta()
    }

    private fun renderMeta() {
        status.text = "${friends.size} 位联系人 · 已显示 $shownPeople（$shownHeads 组）· 接口无分页参数"
    }

    private fun name(f: Api.Friend) = f.nickname.ifEmpty { f.username }

    private fun buildRail(letters: List<String>) {
        rail.removeAllViews()
        val dm = resources.displayMetrics.density
        for (l in letters) {
            val tv = TextView(requireContext())
            tv.text = l
            tv.textSize = 11f
            tv.setTextColor(0xFF8A93A6.toInt())
            tv.gravity = android.view.Gravity.CENTER
            tv.isClickable = true
            tv.layoutParams = LinearLayout.LayoutParams(
                (14 * dm).toInt(), (12 * dm).toInt())
            tv.setOnClickListener { jumpTo(l) }
            rail.addView(tv)
        }
    }

    private fun jumpTo(letter: String) {
        val i = lines.indexOfFirst { it is Head && it.letter == letter }
        if (i >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(p: Int) = if (lines[p] is Head) 0 else 1

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(p.context)
            return if (t == 0) HeadVH(inf.inflate(R.layout.item_contact_head, p, false))
            else PersonVH(inf.inflate(R.layout.item_contact, p, false))
        }

        override fun getItemCount() = lines.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            when (val ln = lines[i]) {
                is Head -> (h as HeadVH).letter.text = ln.letter
                is Person -> {
                    val v = h as PersonVH
                    val nm = name(ln.f)
                    v.ava.text = nm.take(1).uppercase()
                    v.name.text = nm
                    v.sub.text = "@${ln.f.username}"
                    v.itemView.setOnClickListener {
                        android.widget.Toast.makeText(requireContext(), "和 $nm 的会话在「微信」页里开", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private class HeadVH(v: View) : RecyclerView.ViewHolder(v) {
        val letter: TextView = v.findViewById(R.id.letter)
    }

    private class PersonVH(v: View) : RecyclerView.ViewHolder(v) {
        val ava: TextView = v.findViewById(R.id.ava)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
    }
}
