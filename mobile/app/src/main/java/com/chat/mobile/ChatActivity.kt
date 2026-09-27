package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.more).setOnClickListener {
            startActivity(Intent(this, ChatInfoActivity::class.java).putExtra("conv", convId))
        }

        val input = findViewById<EditText>(R.id.input)
        findViewById<Button>(R.id.send).setOnClickListener {
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
            adapter.notifyItemInserted(msgs.size - 1)
            list.scrollToPosition(msgs.size - 1)
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
                adapter.notifyDataSetChanged()
                state.text = "${msgs.size} 条消息"
                if (msgs.isNotEmpty()) list.scrollToPosition(msgs.size - 1)
            }

            "MESSAGE_RECEIVE" -> {
                val d = m.optJSONObject("data") ?: return
                if (d.optString("conversationId") != convId) return
                msgs.add(d)
                adapter.notifyItemInserted(msgs.size - 1)
                list.scrollToPosition(msgs.size - 1)
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

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_message, parent, false))

        override fun getItemCount(): Int = msgs.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val m = msgs[i]
            // senderId 是 JSON 数字，getString 会抛；一律 get().toString()
            val sender = m.opt("senderId")?.toString() ?: ""
            val self = sender.isNotEmpty() && sender == myId
            h.bubble.text = m.optString("content")
            h.bubble.setBackgroundResource(if (self) R.drawable.bubble_self else R.drawable.bubble_other)
            h.bubble.setTextColor(if (self) 0xFFFFFFFF.toInt() else 0xFF1B2434.toInt())
            h.row.gravity = if (self) Gravity.END else Gravity.START
            h.time.gravity = if (self) Gravity.END else Gravity.START
            h.time.text = pretty(m.optString("createTime"))
            val letter = (if (self) mine else convName).ifEmpty { "?" }.take(1).uppercase()
            h.avaL.visibility = if (self) View.GONE else View.VISIBLE
            h.avaR.visibility = if (self) View.VISIBLE else View.GONE
            h.avaL.text = letter
            h.avaR.text = letter
        }
    }

    /** createTime 是带 Z 的 UTC（实测 2026-09-27T05:06:30.586Z 在手机上印成 05:06，
     *  而本机当时是 13:06 —— 差整 8 小时）。必须换算到设备时区再印。 */
    private fun pretty(iso: String): String = try {
        val t = OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        if (t.toLocalDate() == now.toLocalDate())
            t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else
            t.format(DateTimeFormatter.ofPattern("M-d HH:mm"))
    } catch (e: Exception) { "" }

    override fun onDestroy() {
        ws?.close(); ws = null
        super.onDestroy()
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.row)
        val bubble: TextView = v.findViewById(R.id.bubble)
        val time: TextView = v.findViewById(R.id.time)
        val avaL: TextView = v.findViewById(R.id.avaL)
        val avaR: TextView = v.findViewById(R.id.avaR)
    }
}
