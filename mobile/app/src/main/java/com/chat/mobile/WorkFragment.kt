package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 工作台做成微信「服务」那一屏：灰底 + 白卡片 + 四列图标格子。
 *
 * 格子里的条目不是编的——清单和图标描点都在 WorkItems 里，就是桌面端 Workbench.vue
 * 侧栏那三组 11 项，两边改一处能对上。
 *
 * 点一个格子进 WorkListActivity（微信「收藏」那一屏的形状：搜索框 + 分类页签 + 竖排卡片）。
 * 那 11 项后端一个接口都没有（桌面端文件里自己写着"静态壳"），所以进去看见的是形状，
 * 每张卡的标签行写着「未接后端接口」，页底那句话也写明这件事。
 */
class WorkFragment : Fragment() {

    /** 那 11 个入口的清单在 WorkItems 里——点进去的那一屏要用同一份，别抄两遍 */
    private val cards: List<WorkItems.Card> by lazy { WorkItems.cards(requireContext()) }

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
                ic.setColorFilter(getColor(card.tintRes), android.graphics.PorterDuff.Mode.SRC_IN)
                tv.findViewById<TextView>(R.id.name).text = t.name
                /* 点一个入口 = 进那一屏（微信那形状：搜索框 + 分类页签 + 竖排卡片）。
                   里面列的还是这 11 项——每一项真内容要等后端接口，现在点进去看见的是形状 */
                tv.setOnClickListener {
                    startActivity(Intent(requireContext(), WorkListActivity::class.java)
                        .putExtra("title", t.name))
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
