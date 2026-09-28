package com.chat.mobile

import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 工作台某个入口点进去的那一屏，形状照微信「收藏」：搜索框 + 一排分类页签 + 竖排卡片。
 *
 * 列表里是 WorkItems 那 11 项（这 11 项就是桌面端工作台侧栏的全部入口，后端一个接口都没有），
 * 每张卡的标签行写着「未接后端接口」。页签和搜索是真过滤本机这 11 条，不是摆设。
 */
class WorkListActivity : EdgeBackActivity() {

    private var tab = "全部"
    private lateinit var host: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var search: EditText
    private var rows = listOf<WorkItems.Row>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_work_list)
        host = findViewById(R.id.cards)
        tabsRow = findViewById(R.id.tabs)
        search = findViewById(R.id.search)
        findViewById<TextView>(R.id.title).text = intent.getStringExtra("title").orEmpty()
        rows = WorkItems.rows(this)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: Editable?) { render() }
        })
        buildTabs()
        render()
    }

    /** 页签 = 全部 + 三个组名；选中那枚只换 brand 色加粗，不放大不抬高 */
    private fun buildTabs() {
        tabsRow.removeAllViews()
        (listOf("全部") + WorkItems.cards(this).map { it.cap }).forEach { name ->
            val t = TextView(this)
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
        val shown = rows.filter { tab == "全部" || it.cap == tab }
            .filter { q.isEmpty() || it.tile.name.lowercase().contains(q) }

        if (shown.isEmpty()) {
            val e = TextView(this)
            e.text = "没有匹配的条目"
            e.textSize = 13f
            e.setTextColor(getColor(R.color.ink_dim2))
            e.setPadding(dp(14), dp(14), dp(14), dp(14))
            host.addView(e)
            return
        }

        shown.forEachIndexed { i, r ->
            val row = layoutInflater.inflate(R.layout.item_work_row, host, false)
            row.findViewById<TextView>(R.id.name).text = r.tile.name
            row.findViewById<TextView>(R.id.meta).text = "分组：${r.cap}"
            row.findViewById<TextView>(R.id.tag).text = "未接后端接口"
            val ic = row.findViewById<ImageView>(R.id.ic)
            ic.setImageResource(r.tile.icon)
            ic.setColorFilter(r.tint, PorterDuff.Mode.SRC_IN)
            row.isClickable = true
            row.setOnClickListener {
                Toast.makeText(this,
                    "${r.tile.name}：桌面端那一块还是设计稿静态壳，后端没有对应接口，所以这里点不出内容",
                    Toast.LENGTH_LONG).show()
            }
            val lp = row.layoutParams as LinearLayout.LayoutParams
            lp.topMargin = if (i == 0) 0 else dp(10)
            row.layoutParams = lp
            host.addView(row)
        }
    }
}
