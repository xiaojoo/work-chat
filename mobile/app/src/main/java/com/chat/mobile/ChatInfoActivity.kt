package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 聊天页右上角 ⋯ 进来的那一屏。
 *
 * 顶上先摆人：群聊是所有成员（GET :8084 /api/group/{id}/members），单聊是对方和我两颗
 * （对方取自会话列表的 targetId，和桌面端「成员」页签在单聊也渲染宫格同一条口径）。
 * 名字再逐个 GET :8081 /api/user/{id} 补——members 那个 DTO 里没有昵称，桌面端也是这么补的。
 * 每颗都能点：别人 → 好友信息页，自己 → 可改的个人信息页。
 * ＋ 那格只在群聊有（POST /{id}/invite）；桌面端单聊那颗 ＋ 是「转群」，这端还没接那条流程。
 *
 * 微信这一屏还有查找聊天记录、提醒、聊天背景、清空记录、投诉——后端都没有对应接口，不摆。
 * 本机真做得到的两条（消息免打扰、置顶聊天）放在人下面，和长按菜单同一份 ConvFlags。
 */
class ChatInfoActivity : EdgeBackActivity() {

    private lateinit var flags: ConvFlags
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var convId: String
    private var members = listOf<Api.Member>()
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var myId = ""
    private var targetId = ""

    private val isGroup get() = convId.startsWith("g")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_chat_info)
        rows = findViewById(R.id.rows)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        convId = intent.getStringExtra("conv") ?: ""
        myId = Cfg.userId(this)
        flags = ConvFlags(this)
        render()
    }

    /** 从「添加成员」或「好友信息」回来都要重拉一遍，不然新邀请进来的人在这儿看不到 */
    override fun onResume() { super.onResume(); loadPeople() }

    private fun loadPeople() {
        val base = Cfg.apiBase(this); val tk = Cfg.token(this)
        if (base.isEmpty() || tk.isEmpty()) { status.text = "还没登录"; return }
        if (isGroup) {
            val gbase = Cfg.groupBase(this)
            if (gbase.isEmpty()) { status.text = "还没登录"; return }
            thread(name = "members") {
                runCatching { Api(gbase, tk).groupMembers(convId) }
                    .onSuccess { got(it) }
                    .onFailure { fail(it) }
            }
        } else {
            // 单聊没有成员接口，对方是谁要从会话列表里按会话 id 找 targetId
            thread(name = "private") {
                runCatching {
                    Api(base, tk).conversations(myId).firstOrNull { it.id == convId }?.targetId.orEmpty()
                }.onSuccess { id ->
                    targetId = id
                    got(listOf(Api.Member(id, 0), Api.Member(myId, 0)).filter { it.userId.isNotEmpty() })
                }.onFailure { fail(it) }
            }
        }
    }

    private fun fail(t: Throwable) = runOnUiThread {
        if (!isFinishing) { render(); status.text = "人拉取失败：${t.message}" }
    }

    private fun got(list: List<Api.Member>) {
        members = list
        runOnUiThread { if (!isFinishing) render() }
        val base = Cfg.apiBase(this); val tk = Cfg.token(this)
        list.map { it.userId }.filter { it.isNotEmpty() && !names.containsKey(it) }.forEach { id ->
            thread(name = "who-$id") {
                val n = runCatching { Api(base, tk).profile(id).nickname }.getOrNull().orEmpty()
                names[id] = n
                if (n.isNotEmpty()) runOnUiThread { if (!isFinishing) render() }
            }
        }
    }

    private fun render() {
        rows.removeAllViews()

        peopleCard()

        toggle("消息免打扰", flags.muted(convId)) { flags.toggleMuted(convId); render() }
        toggle("置顶聊天", flags.pinned(convId)) { flags.togglePinned(convId); render() }
        Rows.endGroup(rows)

        status.text = (if (isGroup)
            "${members.size} 名成员 · 来自 GET :${Cfg.GROUP_PORT}/api/group/{id}/members"
        else
            "2 人 · 对方取自 GET :${Cfg.USER_PORT}/api/conversation/list 的 targetId；" +
                "桌面端单聊那颗 ＋ 是「转群」，这端没接那条流程，所以这里不摆 ＋") +
            "；免打扰和置顶和会话列表长按那一项是同一份状态（存在本机）"
    }

    /** 一行 5 格、每格恒等于 1/5 宽：见 item_card_rows.xml 顶部那段——GridLayout 不满一行会居中 */
    private fun peopleCard() {
        val card = LayoutInflater.from(this).inflate(R.layout.item_card_rows, rows, false)
        card.findViewById<TextView>(R.id.cap).text = if (isGroup) "群成员" else "聊天成员"
        val host = card.findViewById<LinearLayout>(R.id.gridRows)
        val cells = ArrayList<View>()
        members.forEach { cells.add(memberCell(it)) }
        if (isGroup) cells.add(addCellView())
        cells.chunked(PER_ROW).forEach { line ->
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.weightSum = PER_ROW.toFloat()
            host.addView(r, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            line.forEach { v ->
                r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        rows.addView(card)
    }

    private fun memberCell(m: Api.Member): View {
        val cell = LayoutInflater.from(this).inflate(R.layout.item_member, null, false)
        val nm = names[m.userId].orEmpty().ifEmpty { "用户${m.userId.takeLast(4)}" }
        cell.findViewById<TextView>(R.id.ava).text = nm.take(1).uppercase()
        cell.findViewById<TextView>(R.id.name).text = nm
        // 角色压在头像角上，不拼进名字：拼进去一格宽放不下，"probeA（群主）"会被截断
        val tag = cell.findViewById<TextView>(R.id.tag)
        tag.text = if (m.role == 2) "群主" else if (m.role == 1) "管理员" else ""
        tag.visibility = if (m.role >= 1) View.VISIBLE else View.INVISIBLE
        tag.setBackgroundResource(if (m.role == 2) R.drawable.tag_owner else R.drawable.tag_admin)
        // 和聊天页那颗头像同一个入口：别人 → 好友信息，自己 → 可改的个人信息
        cell.isClickable = true
        cell.setOnClickListener {
            if (m.userId == myId) startActivity(Intent(this, ProfileActivity::class.java))
            else startActivity(Intent(this, PersonActivity::class.java)
                .putExtra("user", m.userId).putExtra("name", nm))
        }
        return cell
    }

    private fun addCellView(): View {
        val add = LayoutInflater.from(this).inflate(R.layout.item_member, null, false)
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
        return add
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

    private companion object { const val PER_ROW = 5 }
}
