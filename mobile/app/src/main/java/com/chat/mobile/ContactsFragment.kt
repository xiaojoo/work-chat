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
import kotlin.concurrent.thread

class ContactsFragment : Fragment() {

    private sealed interface Line
    private data class Head(val letter: String) : Line
    private data class Person(val f: Api.Friend) : Line

    private val lines = ArrayList<Line>()
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var rail: LinearLayout
    private var friends = listOf<Api.Friend>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_contacts, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        list = v.findViewById(R.id.list)
        status = v.findViewById(R.id.status)
        rail = v.findViewById(R.id.rail)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext())
        if (base.isEmpty() || tk.isEmpty()) { status.text = "还没登录"; return }
        status.text = "加载中…"
        thread(name = "friends") {
            try {
                val got = Api(base, tk).friends()
                activity?.runOnUiThread { if (isAdded) { friends = got; rebuild() } }
            } catch (e: Exception) {
                activity?.runOnUiThread { if (isAdded) status.text = "拉取失败：${e.message}" }
            }
        }
    }

    /** 按拼音首字母分组，组内也按拼音排 */
    private fun rebuild() {
        lines.clear()
        val grouped = LinkedHashMap<String, ArrayList<Api.Friend>>()
        for (f in friends.sortedWith { a, b -> Pinyin.compare(name(a), name(b)) }) {
            val l = Pinyin.initial(name(f))
            grouped.getOrPut(l) { ArrayList() }.add(f)
        }
        for ((l, fs) in grouped) {
            lines.add(Head(l))
            fs.forEach { lines.add(Person(it)) }
        }
        list.adapter?.notifyDataSetChanged()
        buildRail(grouped.keys.toList())
        status.text = "${friends.size} 位联系人"
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
