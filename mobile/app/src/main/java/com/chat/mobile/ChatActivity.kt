package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class ChatActivity : EdgeBackActivity() {

    private lateinit var convId: String
    private lateinit var convName: String
    private var myId = ""
    private var mine = ""
    private var ws: Ws? = null

    private lateinit var list: RecyclerView
    private lateinit var state: TextView
    private val msgs = ArrayList<JSONObject>()
    private lateinit var adapter: Adapter
    private var pop: android.widget.PopupWindow? = null

    /** 服务端也按 2 分钟判，这里只是不把必然失败的入口摆出来（和桌面端同一个数） */
    private val REVOKE_MS = 2 * 60 * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        convId = intent.getStringExtra("conv") ?: ""
        convName = intent.getStringExtra("name") ?: ""
        myId = Cfg.userId(this)
        mine = Cfg.username(this)

        findViewById<TextView>(R.id.title).text = convName
        state = findViewById(R.id.state)
        list = findViewById(R.id.messages)
        adapter = Adapter()
        /* 不 stackFromEnd：消息不满一屏时也要从顶上往下铺，底部留白看起来像没加载。
           满了照样靠下面那几处 scrollToPosition 停在最新一条 */
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        loadNames()
        findViewById<Button>(R.id.more).setOnClickListener {
            startActivity(Intent(this, ChatInfoActivity::class.java).putExtra("conv", convId))
        }

        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<Button>(R.id.send)
        /* 没字的时候这颗是灰的：省得点一下什么都不发生，还以为按不动 */
        send.isEnabled = false
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                send.isEnabled = !s.toString().trim().isEmpty()
            }
        })
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty() || convId.isEmpty()) return@setOnClickListener
            // 网关的 MESSAGE_SEND 只认 conversationId / messageType / content / extra 四个键
            ws?.send("MESSAGE_SEND", JSONObject()
                .put("conversationId", convId)
                .put("messageType", "TEXT")
                .put("content", text)
                .put("extra", ""))
            /* 网关不会把消息回推给发送方（实测：发完 LOAD_MESSAGES 里才出现它），
               所以自己这条要当场摆上，否则"发出去了但界面上没有"。和桌面端同一条规则 */
            msgs.add(JSONObject()
                .put("conversationId", convId)
                .put("senderId", myId)
                .put("messageType", "TEXT")
                .put("content", text)
                .put("createTime", OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()))
            rebuildRows(); adapter.notifyDataSetChanged()
            list.scrollToPosition(rows.size - 1)
            input.setText("")
        }

        if (convId.isEmpty() || myId.isEmpty()) { state.text = "参数不全，回列表重进" ; return }
        // 进会话就清未读：桌面端是同一条规则（点开 = 已读）。
        // 失败要喊出来——之前 runCatching 把它咽了，清不掉时界面上完全看不出来
        thread(name = "clear-unread") {
            try {
                Api(Cfg.apiBase(this), Cfg.token(this)).clearUnread(convId, myId)
            } catch (e: Exception) {
                runOnUiThread { state.text = "清未读失败：${e.message}" }
            }
        }

        val w = Ws(Cfg.wsBase(this), Cfg.token(this), "chat-mobile",
            onFrame = { onFrame(it) }, onState = { runOnUiThread { state.text = it } })
        ws = w
        w.open()
        // 连上再拉历史：open 是异步的，这里等一等比把 LOAD 塞进 onOpen 里简单
        thread(name = "load-delay") {
            Thread.sleep(1200)
            runOnUiThread { ws?.send("LOAD_MESSAGES", JSONObject().put("conversationId", convId).put("limit", 50)) }
        }
    }

    private fun onFrame(m: JSONObject) {
        when (m.optString("type")) {
            "LOAD_MESSAGES" -> {
                val arr = when (val raw = m.opt("data")) {
                    is org.json.JSONArray -> raw
                    // 网关有时把 data 塞成字符串（桌面端也是两种都认）
                    is String -> runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return
                    else -> return
                }
                // 服务端给的是新→旧，界面要倒过来（和桌面端 data.reverse() 一致）
                val got = ArrayList<JSONObject>()
                for (i in 0 until arr.length()) got.add(arr.getJSONObject(i))
                msgs.clear(); msgs.addAll(got.reversed())
                rebuildRows(); adapter.notifyDataSetChanged()
                state.text = "${msgs.size} 条消息"
                if (rows.isNotEmpty()) list.scrollToPosition(rows.size - 1)
            }

            "MESSAGE_RECEIVE" -> {
                val d = m.optJSONObject("data") ?: return
                if (d.optString("conversationId") != convId) return
                msgs.add(d)
                rebuildRows(); adapter.notifyDataSetChanged()
                list.scrollToPosition(rows.size - 1)
                state.text = "${msgs.size} 条消息"
                /* 正开着这条、画面就在眼前时来消息，服务端照加未读（它不知道你在看）。
                   不在这里抹掉，回列表点刷新就会挂出一个假红标——桌面端量到过同一笔漂移 */
                thread(name = "clear-unread-live") {
                    runCatching { Api(Cfg.apiBase(this@ChatActivity), Cfg.token(this@ChatActivity))
                        .clearUnread(convId, myId) }
                }
            }

            "MESSAGE_ACK" -> {
                val d = m.optJSONObject("data") ?: return
                state.text = if (d.optString("status") == "SENT") "已送达" else "发送失败：${d.optString("status")}"
            }
        }
    }

    /** 界面上的一行：一条消息，或一条居中时间分隔 */
    private sealed interface Row
    private data class MsgRow(val m: JSONObject) : Row
    private data class TimeRow(val text: String) : Row

    private val rows = ArrayList<Row>()

    /** 桌面端 shouldShowTime 的同一套：第一条、或和上一条隔够 5 分钟，才插一条居中分隔；
     *  时间读不出来的那条不插空药丸 */
    private fun rebuildRows() {
        rows.clear()
        var prev = 0L
        msgs.forEachIndexed { i, m ->
            val t = tsOf(m)
            if ((i == 0 || prev == 0L || t - prev >= 5 * 60 * 1000L) && t > 0L) rows.add(TimeRow(divider(t)))
            if (t > 0L) prev = t
            rows.add(MsgRow(m))
        }
    }

    private fun divider(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        val hm = z.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (z.toLocalDate()) {
            now.toLocalDate() -> "今天 $hm"
            now.toLocalDate().minusDays(1) -> "昨天 $hm"
            else -> if (z.year == now.year) z.format(DateTimeFormatter.ofPattern("M月d日 $hm"))
                    else z.format(DateTimeFormatter.ofPattern("yyyy年M月d日 $hm"))
        }
    }

    /** 群聊才印发送人名（桌面端 .msg-who）。名字先查好友表，查不到再逐个 /api/user/{id} 补，
     *  和桌面端 loadMemberNames → resolveSenderName 同一条路径 */
    /** 补名字是在后台线程写的、界面在主线程读，所以用 ConcurrentHashMap，不用普通 HashMap */
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var myName = ""
    private val isGroup get() = convId.startsWith("g")

    private fun nameOf(id: String): String = when {
        id.isEmpty() -> ""
        id == myId -> myName.ifEmpty { mine }
        names.containsKey(id) -> names[id] ?: ""
        else -> { lookup(id); "" }
    }

    private fun lookup(id: String) {
        thread(name = "who-$id") {
            val n = runCatching { Api(Cfg.apiBase(this@ChatActivity), Cfg.token(this@ChatActivity)).profile(id).nickname }
                .getOrNull().orEmpty()
            names[id] = n
            if (n.isNotEmpty()) runOnUiThread { if (!isFinishing) adapter.notifyDataSetChanged() }
        }
    }

    private fun loadNames() {
        thread(name = "names") {
            val base = Cfg.apiBase(this); val tk = Cfg.token(this)
            runCatching {
                Api(base, tk).friends().forEach { names[it.id] = it.nickname.ifEmpty { it.username } }
                myName = Api(base, tk).profile(myId).nickname
            }
            runOnUiThread { if (!isFinishing) adapter.notifyDataSetChanged() }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(p: Int) = if (rows[p] is TimeRow) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
            if (type == 1) TimeVH(layoutInflater.inflate(R.layout.item_time, parent, false))
            else VH(layoutInflater.inflate(R.layout.item_message, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            when (val r = rows[i]) {
                is TimeRow -> (h as TimeVH).at.text = r.text
                is MsgRow -> bindMsg(h as VH, r.m)
            }
        }
    }

    private fun bindMsg(h: VH, m: JSONObject) {
        // senderId 是 JSON 数字，getString 会抛；一律 get().toString()
        val sender = m.opt("senderId")?.toString() ?: ""
        val self = sender.isNotEmpty() && sender == myId
        h.bubble.text = m.optString("content")
        h.bubble.setBackgroundResource(if (self) R.drawable.bubble_self else R.drawable.bubble_other)
        h.bubble.setTextColor(if (self) 0xFFFFFFFF.toInt() else 0xFF1B2434.toInt())
        /* 群聊才印发送人名；单聊不印（桌面端 .msg-who 也是这个口径）。
           名字就在行内那一列的头，所以有名字时整行改成顶部对齐——名字的顶边就和头像的顶边一条线。
           单聊那一行没名字，头像继续跟着气泡垂直居中（之前认可的样子不动）。
           尾巴不受这两条影响：它和气泡同属 line 那一横排，那一排自己 center_vertical。 */
        val who = if (isGroup) nameOf(sender) else ""
        h.row.gravity = (if (who.isEmpty()) Gravity.CENTER_VERTICAL else Gravity.TOP) or
            (if (self) Gravity.END else Gravity.START)
        h.col.gravity = if (self) Gravity.END else Gravity.START
        val letter = (if (self) nameOf(myId) else nameOf(sender)).ifEmpty {
            (if (self) mine else convName)
        }.take(1).uppercase()
        h.avaL.visibility = if (self) View.GONE else View.VISIBLE
        h.avaR.visibility = if (self) View.VISIBLE else View.GONE
        h.avaL.text = letter
        h.avaR.text = letter
        // 尾巴和气泡同色，跟着一起翻边：对方在左指、自己在右指
        h.tailL.visibility = if (self) View.GONE else View.VISIBLE
        h.tailR.visibility = if (self) View.VISIBLE else View.GONE
        val tail = if (self) 0xFF2B6BE8.toInt() else 0xFFF0F3F8.toInt()
        h.tailL.setColorFilter(tail, android.graphics.PorterDuff.Mode.SRC_IN)
        h.tailR.setColorFilter(tail, android.graphics.PorterDuff.Mode.SRC_IN)
        h.who.text = who
        h.who.visibility = if (who.isEmpty()) View.GONE else View.VISIBLE
        /* 名字对齐的是气泡那个方块，方块外侧还压着 6dp 宽的尾巴，所以让开一个尾巴的宽度。
           以前那个 60dp 是给「整行之上」那种排法让头像的，名字进了列里就不需要了 */
        val tailW = (6 * resources.displayMetrics.density).toInt()
        h.who.setPadding(if (self) 0 else tailW, 0, if (self) tailW else 0, 0)
        /* 长按要挂在整行、不能挂在气泡上：气泡是 TextView，MIUI 的「文本识别」会先吃掉
           TextView 自己的长按（实测弹出来的是系统的识别条，我们的菜单根本没出现）。
           整行是 LinearLayout，没有这套内置处理。 */
        h.bubble.setTextIsSelectable(false)
        h.itemView.setOnLongClickListener { showMsgMenu(h.bubble, m, self); true }
        /* 点头像看这个人：对方 → 好友信息整页（桌面端 friendCard 那份字段）；
           自己 → 我的资料那页，因为那页能改，好友信息页只能看 */
        h.avaL.setOnClickListener { openPerson(sender, who.ifEmpty { nameOf(sender) }) }
        h.avaR.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun openPerson(id: String, nm: String) {
        if (id.isEmpty()) return
        startActivity(Intent(this, PersonActivity::class.java)
            .putExtra("user", id).putExtra("name", nm.ifEmpty { "用户${id.takeLast(4)}" }))
    }

    /** 长按一条消息 → 桌面端右键那十二项，这里改成五列一行的格子（微信那种双行排法）。
     *  真能做的只有「复制」和「撤回」，其余按桌面端同样的口径画淡、点了不响应。 */
    private fun showMsgMenu(anchor: View, m: JSONObject, self: Boolean) {
        val isText = (m.optString("messageType").ifEmpty { "TEXT" }) == "TEXT"
        val canRevoke = self && !mId(m).startsWith("local-") && mId(m).isNotEmpty() &&
            System.currentTimeMillis() - tsOf(m) <= REVOKE_MS
        val items = listOf(
            MItem("复制", R.drawable.ic_m_copy, isText) { copyMsg(m) },
            MItem("放大阅读", R.drawable.ic_m_zoom, false),
            MItem("翻译", R.drawable.ic_m_translate, false),
            MItem("搜一搜", R.drawable.ic_m_menusearch, false),
            MItem("转发…", R.drawable.ic_m_forward, false),
            MItem("收藏", R.drawable.ic_m_menustar, false),
            MItem("多选", R.drawable.ic_m_multi, false),
            MItem("提醒", R.drawable.ic_m_remind, false),
            MItem("引用", R.drawable.ic_m_quote, false),
            MItem("删除", R.drawable.ic_m_trash, false),
            MItem("撤回", R.drawable.ic_m_undo, canRevoke) { confirmRevoke(m) }
        )
        val grid = layoutInflater.inflate(R.layout.popup_msg_menu, null) as GridLayout
        items.forEach { it0 ->
            val cell = layoutInflater.inflate(R.layout.item_msg_menu, grid, false)
            cell.findViewById<ImageView>(R.id.ic).setImageResource(it0.icon)
            cell.findViewById<TextView>(R.id.name).text = it0.name
            cell.alpha = if (it0.on) 1f else 0.4f
            if (it0.on) cell.setOnClickListener { pop?.dismiss(); it0.run() }
            else cell.isClickable = false
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            })
        }
        val p = PopupWindow(grid,
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.isOutsideTouchable = true
        p.elevation = 12f * resources.displayMetrics.density
        pop = p
        p.setOnDismissListener { pop = null }
        /* 格子是五列 columnWeight 均分，用 UNSPECIFIED 去量会得到宽度 0 ——
           弹出来就是一个看不见的窗。所以定宽 5×62dp，再交给 showAsDropDown 自己决定上下翻。 */
        val w = (62 * 5 * resources.displayMetrics.density).toInt()
        p.width = w
        /* 以整行为参照居中（气泡贴左也贴右，跟着气泡走会顶出屏幕），
           纵向交给 showAsDropDown：底下放不下它自己翻到上面 */
        val row = anchor.parent as View
        val a = IntArray(2); anchor.getLocationInWindow(a)
        val r = IntArray(2); row.getLocationInWindow(r)
        val xoff = (r[0] + row.width / 2 - a[0]) - w / 2
        p.showAsDropDown(anchor, xoff, (8 * resources.displayMetrics.density).toInt())
    }

    private data class MItem(val name: String, val icon: Int, val on: Boolean, val run: () -> Unit = {})

    /** 服务端回来的这条消息的 id：本地回显的那份是 local- 开头，没有可撤回的对象 */
    private fun mId(m: JSONObject): String =
        (m.opt("messageId") ?: m.opt("id"))?.toString() ?: ""

    private fun tsOf(m: JSONObject): Long = try {
        val t = m.optString("createTime")
        if (t.isEmpty()) 0L else OffsetDateTime.parse(t).toInstant().toEpochMilli()
    } catch (e: Exception) { 0L }

    private fun copyMsg(m: JSONObject) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.optString("content")))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRevoke(m: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("撤回这条消息")
            .setMessage("发出去不超过 2 分钟才让撤。撤回后这条在服务器上是 DELETED，两边界面都会变。")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("撤回") { _, _ ->
                ws?.send("MESSAGE_DELETE", JSONObject()
                    .put("conversationId", convId)
                    .put("messageId", mId(m)))
                Toast.makeText(this, "已发撤回", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 原来这里有个 pretty(iso)：把时间印在每条气泡底下。时间改成居中分隔后它就没主了，删掉。
     *  留一句要紧的事实给 tsOf：createTime 是带 Z 的 UTC（实测 2026-09-27T05:06:30.586Z
     *  在手机上直接印成 05:06，而本机当时是 13:06 —— 差整 8 小时），必须换算到设备时区。 */
    override fun onDestroy() {
        ws?.close(); ws = null
        super.onDestroy()
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.row)
        val col: LinearLayout = v.findViewById(R.id.col)
        val bubble: TextView = v.findViewById(R.id.bubble)
        val who: TextView = v.findViewById(R.id.who)
        val avaL: TextView = v.findViewById(R.id.avaL)
        val avaR: TextView = v.findViewById(R.id.avaR)
        val tailL: ImageView = v.findViewById(R.id.tailL)
        val tailR: ImageView = v.findViewById(R.id.tailR)
    }

    private class TimeVH(v: View) : RecyclerView.ViewHolder(v) {
        val at: TextView = v.findViewById(R.id.at)
    }
}
