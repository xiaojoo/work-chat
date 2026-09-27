package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlin.concurrent.thread

/**
 * 聊天页右上角 ⋯ 进来的那一屏。
 *
 * 群聊时顶上先摆成员宫格（一行 5 格，头像 + 名字，角色压在头像角上），最后一格是 ＝ 添加成员；
 * 成员来自 GET :8084 /api/group/{id}/members，名字再逐个 GET :8081 /api/user/{id} 补，
 * 和桌面端「成员」页签读的是同一份。邀请走 POST /{id}/invite。
 *
 * 微信这一屏还有查找聊天记录、提醒、聊天背景、清空记录、投诉——后端都没有对应接口，不摆。
 * 本机真做得到的两条（消息免打扰、置顶聊天）放在成员下面，和长按菜单同一份 ConvFlags。
 */
class ChatInfoActivity : EdgeBackActivity() {

    private lateinit var flags: ConvFlags
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var convId: String
    private var members = listOf<Api.Member>()
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val isGroup get() = convId.startsWith("g")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_chat_info)
        rows = findViewById(R.id.rows)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        convId = intent.getStringExtra("conv") ?: ""
        flags = ConvFlags(this)
        render()
    }

    /** 从「添加成员」那页回来要重拉一遍，不然新邀请进来的人在这儿看不到 */
    override fun onResume() {
        super.onResume()
        if (isGroup) loadMembers() else render()
    }

    private fun loadMembers() {
        val gbase = Cfg.groupBase(this); val base = Cfg.apiBase(this); val tk = Cfg.token(this)
        if (gbase.isEmpty() || tk.isEmpty()) { status.text = "还没登录"; return }
        thread(name = "members") {
            val res = runCatching { Api(gbase, tk).groupMembers(convId) }
            res.onSuccess { list ->
                members = list
                runOnUiThread { render() }
                list.map { it.userId }.filter { it.isNotEmpty() && !names.containsKey(it) }.forEach { id ->
                    thread(name = "who-$id") {
                        val n = runCatching { Api(base, tk).profile(id).nickname }.getOrNull().orEmpty()
                        names[id] = n
                        if (n.isNotEmpty()) runOnUiThread { if (!isFinishing) render() }
                    }
                }
            }.onFailure {
                runOnUiThread { if (!isFinishing) { render(); status.text = "成员拉取失败：${it.message}" } }
            }
        }
    }

    private fun render() {
        rows.removeAllViews()

        if (isGroup) memberCard()

        toggle("消息免打扰", flags.muted(convId)) { flags.toggleMuted(convId); render() }
        toggle("置顶聊天", flags.pinned(convId)) { flags.togglePinned(convId); render() }
        Rows.endGroup(rows)

        status.text = if (isGroup)
            "${members.size} 名成员 · 来自 GET :${Cfg.GROUP_PORT}/api/group/{id}/members；" +
                "免打扰和置顶和会话列表长按那一项是同一份状态（存在本机）"
        else "这两条和会话列表长按那一项是同一份状态（存在本机）；后端没有对应字段。"
    }

    /** 成员宫格：一行 5 格，最后一格是 ＋ */
    private fun memberCard() {
        val card = LayoutInflater.from(this).inflate(R.layout.item_card, rows, false)
        card.findViewById<TextView>(R.id.cap).text = "群成员"
        val grid = card.findViewById<GridLayout>(R.id.grid)
        // item_card 那份默认四列是工作台在用的，列数只在这儿改，不动共享布局
        grid.columnCount = 5
        val inf = LayoutInflater.from(this)
        members.forEach { m ->
            val cell = inf.inflate(R.layout.item_member, grid, false)
            val nm = names[m.userId].orEmpty().ifEmpty { "用户${m.userId.takeLast(4)}" }
            cell.findViewById<TextView>(R.id.ava).text = nm.take(1).uppercase()
            cell.findViewById<TextView>(R.id.name).text = nm
            // 角色压在头像角上，不拼进名字：拼进去那一列宽放不下，"probeA（群主）"会被截断
            val tag = cell.findViewById<TextView>(R.id.tag)
            tag.text = if (m.role == 2) "群主" else if (m.role == 1) "管理员" else ""
            tag.visibility = if (m.role >= 1) View.VISIBLE else View.INVISIBLE
            tag.setBackgroundResource(if (m.role == 2) R.drawable.tag_owner else R.drawable.tag_admin)
            cell.isClickable = false
            addCell(grid, cell)
        }
        val add = inf.inflate(R.layout.item_member, grid, false)
        val ava = add.findViewById<TextView>(R.id.ava)
        ava.text = "＋"
        ava.textSize = 20f
        ava.setBackgroundResource(R.drawable.add_square)
        ava.setTextColor(getColor(R.color.ink_dim))
        add.findViewById<TextView>(R.id.name).text = "添加成员"
        add.setOnClickListener {
            startActivity(Intent(this, AddMembersActivity::class.java)
                .putExtra("conv", convId)
                .putExtra("have", members.joinToString(",") { it.userId }))
        }
        addCell(grid, add)
        rows.addView(card)
    }

    private fun addCell(grid: GridLayout, cell: View) {
        grid.addView(cell, GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
        })
    }

    /** 开关不用系统那枚 Switch，自己画底槽和滑块；整行可点，那颗药丸只有 40x24dp，按它太窄 */
    private fun toggle(label: String, on: Boolean, onClick: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_toggle, rows, false)
        v.findViewById<TextView>(R.id.label).text = label
        val track = v.findViewById<FrameLayout>(R.id.track)
        val thumb = v.findViewById<View>(R.id.thumb)
        track.setBackgroundResource(if (on) R.drawable.switch_track_on else R.drawable.switch_track_off)
        val lp = thumb.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (on) android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                     else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        val m = (2 * resources.displayMetrics.density).toInt()
        lp.marginStart = if (on) 0 else m
        lp.marginEnd = if (on) m else 0
        thumb.layoutParams = lp
        v.findViewById<View>(R.id.tappable).setOnClickListener { onClick() }
        rows.addView(v)
    }
}
