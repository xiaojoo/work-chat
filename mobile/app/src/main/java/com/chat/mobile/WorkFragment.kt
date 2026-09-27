package com.chat.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * 工作台做成微信「服务」那一屏：灰底 + 白卡片 + 四列图标格子。
 *
 * 格子里的条目不是编的——就是桌面端 Workbench.vue 侧栏那三组 11 项
 * （首页/我的任务/最近内容/收藏、项目/文档/素材/知识库、AI 助手/内容生成/内容分析），
 * 图标描点也直接搬它那套 path，不另画一份，这样两边以后改图标只改一处能对上。
 *
 * 但这 11 项后端一个接口都没有（桌面端文件里自己写着"静态壳"），
 * 所以点了给一句原因，不摆点了没反应的死格子；底部那句话也写明这件事。
 */
class WorkFragment : Fragment() {

    private data class Tile(val name: String, val icon: Int)
    private data class Card(val cap: String, val tint: Int, val tiles: List<Tile>)

    private val cards: List<Card> by lazy {
        val brand = 0xFF2B6BE8.toInt()   // --brand
        val ok = getColor(R.color.ok)
        val warn = getColor(R.color.warn)
        listOf(
            Card("工作台", brand, listOf(
                Tile("首页", R.drawable.ic_wb_home), Tile("我的任务", R.drawable.ic_wb_task),
                Tile("最近内容", R.drawable.ic_wb_inbox), Tile("收藏", R.drawable.ic_wb_star))),
            Card("内容", ok, listOf(
                Tile("项目", R.drawable.ic_wb_folder), Tile("文档", R.drawable.ic_wb_doc),
                Tile("素材", R.drawable.ic_wb_image), Tile("知识库", R.drawable.ic_wb_book))),
            Card("AI", warn, listOf(
                Tile("AI 助手", R.drawable.ic_wb_spark), Tile("内容生成", R.drawable.ic_wb_pen),
                Tile("内容分析", R.drawable.ic_wb_chart)))
        )
    }

    private fun getColor(id: Int) = requireContext().getColor(id)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_work, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        val host = v.findViewById<LinearLayout>(R.id.cards)
        val inf = LayoutInflater.from(requireContext())
        cards.forEachIndexed { ci, card ->
            val cv = inf.inflate(R.layout.item_card, host, false)
            cv.findViewById<TextView>(R.id.cap).text = card.cap
            val grid = cv.findViewById<GridLayout>(R.id.grid)
            card.tiles.forEach { t ->
                val tv = inf.inflate(R.layout.item_tile, grid, false)
                val ic = tv.findViewById<ImageView>(R.id.ic)
                ic.setImageResource(t.icon)
                ic.setColorFilter(card.tint, android.graphics.PorterDuff.Mode.SRC_IN)
                tv.findViewById<TextView>(R.id.name).text = t.name
                tv.setOnClickListener {
                    Toast.makeText(requireContext(),
                        "${t.name}：桌面端那一块还是设计稿静态壳，后端没有对应接口，所以这里点不出内容",
                        Toast.LENGTH_LONG).show()
                }
                grid.addView(tv, GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                })
            }
            if (ci > 0) {
                (cv.layoutParams as LinearLayout.LayoutParams).topMargin =
                    (10 * resources.displayMetrics.density).toInt()
            }
            host.addView(cv)
        }
    }
}
