package com.chat.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/**
 * 聊天信息页那颗 ＋ 点进来的一页：按部门列公司里的人，点一个就邀请进群。
 * 数据是 /api/user/org，和桌面端「添加用户」那棵树同一份；已经在群里的人不列出来。
 */
class AddMembersActivity : EdgeBackActivity() {

    private sealed interface Row
    private data class Dept(val name: String) : Row
    private data class Person(val p: Api.OrgPerson) : Row

    private var rows = listOf<Row>()
    private lateinit var convId: String
    private var have = setOf<String>()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_add_members)
        convId = intent.getStringExtra("conv") ?: ""
        have = (intent.getStringExtra("have") ?: "").split(',').filter { it.isNotEmpty() }.toSet()

        val list = findViewById<RecyclerView>(R.id.list)
        val status = findViewById<TextView>(R.id.status)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemViewType(p: Int) = if (rows[p] is Dept) 0 else 1
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
                val inf = LayoutInflater.from(parent.context)
                return if (type == 0) HeadVH(inf.inflate(R.layout.item_contact_head, parent, false))
                else PersonVH(inf.inflate(R.layout.item_contact, parent, false))
            }
            override fun getItemCount() = rows.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                when (val r = rows[i]) {
                    is Dept -> (h as HeadVH).letter.text = r.name
                    is Person -> {
                        val v = h as PersonVH
                        val nm = r.p.nickname.ifEmpty { r.p.username }
                        v.ava.text = nm.take(1).uppercase()
                        v.name.text = nm
                        v.sub.text = listOf(r.p.position, r.p.username).filter { it.isNotEmpty() }.joinToString(" · ")
                        v.line.visibility = View.VISIBLE
                        v.itemView.setOnClickListener { confirm(r.p, nm) }
                    }
                }
            }
        }

        val base = Cfg.apiBase(this); val tk = Cfg.token(this)
        if (base.isEmpty() || tk.isEmpty()) { status.text = "还没登录"; return }
        status.text = "读取组织架构中…"
        thread(name = "org") {
            val res = runCatching { Api(base, tk).org() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess { depts ->
                    val built = ArrayList<Row>()
                    depts.forEach { d ->
                        val out = d.people.filter { it.id.isNotEmpty() && !have.contains(it.id) }
                        if (out.isNotEmpty()) {
                            built.add(Dept(d.name.ifEmpty { "未分组" }))
                            out.forEach { built.add(Person(it)) }
                        }
                    }
                    rows = built
                    list.adapter?.notifyDataSetChanged()
                    status.text = "${built.count { it is Person }} 人可添加 · 已在群里的 ${have.size} 人不列出"
                }.onFailure { status.text = "读取失败：${it.message}" }
            }
        }
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
    }

    private fun confirm(p: Api.OrgPerson, nm: String) {
        AlertDialog.Builder(this)
            .setTitle("邀请 $nm 进群")
            .setMessage("调 POST /api/group/{id}/invite，邀请后这个群的人数立刻跟着变。")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("邀请") { _, _ -> doInvite(p, nm) }
            .show()
    }

    private fun doInvite(p: Api.OrgPerson, nm: String) {
        val gbase = Cfg.groupBase(this); val tk = Cfg.token(this)
        thread(name = "invite") {
            val res = runCatching { Api(gbase, tk).invite(convId, listOf(p.id)) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                // 成功就退出这一页，让聊天信息页 onResume 重新拉一次成员，别在本页自己拼一份
                res.onSuccess {
                    Toast.makeText(this, "已邀请 $nm", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure {
                    Toast.makeText(this, "邀请失败：${it.message}（群服务在 :${Cfg.GROUP_PORT}，不通的话先确认它起来了）",
                        Toast.LENGTH_LONG).show()
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
        val line: View = v.findViewById(R.id.line)
    }
}
