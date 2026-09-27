package com.chat.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 工作台做成微信「服务」那种分组行。
 * 四块内容在桌面端就是静态壳（Workbench.vue 从 src/mock/workbench 取数，
 * 文件里自己写着"后端还没有对应接口，所以按钮先置灰"），手机上不复制假数据：
 * 行照常列出来、写明没有接口，点了给一句原因，不做点了没反应的死控件。
 */
class WorkFragment : Fragment() {

    private data class Entry(val key: String, val name: String, val tile: String, val color: Int)

    private val entries = listOf(
        Entry("home", "首页看板", "看", 0xFF2B6BE8.toInt()),
        Entry("tasks", "我的任务", "办", 0xFF3AAE77.toInt()),
        Entry("docs", "项目文档", "档", 0xFFE08A1E.toInt()),
        Entry("ai", "AI 助手", "AI", 0xFF8B5CF6.toInt()))

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_work, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_service, p, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val e = entries[i]
            h.tile.text = e.tile
            h.tile.setBackgroundColor(e.color)
            h.name.text = e.name
            h.desc.text = "后端还没有对应接口"
            h.state.text = "未开放"
            h.name.alpha = 0.55f
            h.itemView.alpha = 0.75f
            h.itemView.setOnClickListener {
                Toast.makeText(requireContext(),
                    "${e.name}：桌面端那四块目前是设计稿静态壳，接口还没做，所以这里先置灰",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: TextView = v.findViewById(R.id.tile)
        val name: TextView = v.findViewById(R.id.name)
        val desc: TextView = v.findViewById(R.id.desc)
        val state: TextView = v.findViewById(R.id.state)
    }
}
