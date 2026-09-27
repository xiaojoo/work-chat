package com.chat.mobile

import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * 工作台照微信「收藏」那一屏的形状：搜索框 + 一排分类页签 + 竖排卡片列表。
 *
 * 条目不是编的——就是桌面端 Workbench.vue 侧栏那三组 11 项
 * （首页/我的任务/最近内容/收藏、项目/文档/素材/知识库、AI 助手/内容生成/内容分析），
 * 图标描点也直接搬它那套 path，不另画一份，这样两边以后改图标只改一处能对上。
 *
 * 页签和搜索都是真过滤（过滤本机这 11 条）。每张卡的标签行写着「未接后端接口」，
 * 点了也给一句原因——原来页底那段同样的说明删了，同一件事不在一页上说两遍。
 */
class WorkFragment : Fragment() {

    private data class Tile(val name: String, val icon: Int)
    private data class Card(val cap: String, val tint: Int, val tiles: List<Tile>)

    private val cards: List<Card> by lazy {
        listOf(
            Card("工作台", getColor(R.color.brand), listOf(
                Tile("首页", R.drawable.ic_wb_home), Tile("我的任务", R.drawable.ic_wb_task),
                Tile("最近内容", R.drawable.ic_wb_inbox), Tile("收藏", R.drawable.ic_wb_star))),
            Card("内容", getColor(R.color.ok), listOf(
                Tile("项目", R.drawable.ic_wb_folder), Tile("文档", R.drawable.ic_wb_doc),
                Tile("素材", R.drawable.ic_wb_image), Tile("知识库", R.drawable.ic_wb_book))),
            Card("AI", getColor(R.color.warn), listOf(
                Tile("AI 助手", R.drawable.ic_wb_spark), Tile("内容生成", R.drawable.ic_wb_pen),
                Tile("内容分析", R.drawable.ic_wb_chart)))
        )
    }

    private var tab = "全部"
    private lateinit var host: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var search: EditText

    private fun getColor(id: Int) = requireContext().getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_work, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        host = v.findViewById(R.id.cards)
        tabsRow = v.findViewById(R.id.tabs)
        search = v.findViewById(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: Editable?) { render() }
        })
        buildTabs()
        render()
    }

    /** 页签就是「全部 + 三个分组名」，选中那枚换 brand 色加粗——只改颜色，不放大不抬高 */
    private fun buildTabs() {
        tabsRow.removeAllViews()
        (listOf("全部") + cards.map { it.cap }).forEach { name ->
            val t = TextView(requireContext())
            t.text = name
            t.textSize = 14f
            t.isClickable = true
            t.setPadding(dp(10), dp(6), dp(10), dp(6))
            t.setTextColor(getColor(if (tab == name) R.color.brand else R.color.ink_dim))
            t.setTypeface(null, if (tab == name) Typeface.BOLD else Typeface.NORMAL)
            t.setOnClickListener { if (tab != name) { tab = name; buildTabs(); render() } }
            tabsRow.addView(t)
        }
    }

    private fun render() {
        host.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val shown = cards.filter { tab == "全部" || it.cap == tab }
            .flatMap { c -> c.tiles.map { Triple(c.cap, c.tint, it) } }
            .filter { q.isEmpty() || it.third.name.lowercase().contains(q) }

        if (shown.isEmpty()) {
            val e = TextView(requireContext())
            e.text = "没有匹配的条目"
            e.textSize = 13f
            e.setTextColor(getColor(R.color.ink_dim2))
            e.setPadding(dp(14), dp(14), dp(14), dp(14))
            host.addView(e)
            return
        }

        shown.forEachIndexed { i, (cap, tint, tile) ->
            val row = layoutInflater.inflate(R.layout.item_work_row, host, false)
            row.findViewById<TextView>(R.id.name).text = tile.name
            row.findViewById<TextView>(R.id.meta).text = "分组：$cap"
            row.findViewById<TextView>(R.id.tag).text = "未接后端接口"
            val ic = row.findViewById<ImageView>(R.id.ic)
            ic.setImageResource(tile.icon)
            ic.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            row.isClickable = true
            row.setOnClickListener {
                Toast.makeText(requireContext(),
                    "${tile.name}：桌面端那一块还是设计稿静态壳，后端没有对应接口，所以这里点不出内容",
                    Toast.LENGTH_LONG).show()
            }
            val lp = row.layoutParams as LinearLayout.LayoutParams
            lp.topMargin = if (i == 0) 0 else dp(10)
            row.layoutParams = lp
            host.addView(row)
        }
    }
}
