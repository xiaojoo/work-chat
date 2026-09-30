package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.FrameLayout
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

    /* ---- 会话内检索 ----
       走网关的 SEARCH_MESSAGES，不直打消息服务的 REST：成员资格只有网关判得动（成员表在用户服务/群服务），
       而检索是最宽的读口，一个常用字能把整条会话翻出来。结果按 requestId 认领——
       连敲两个字时上一次的回执不能把这一次盖掉。口径和桌面端 Chat.vue 那一份对齐。 */
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchPanel: View
    private lateinit var searchMeta: TextView
    private lateinit var searchNote: TextView
    private lateinit var searchList: RecyclerView
    private lateinit var sumRow: View
    private lateinit var sumBtn: Button
    private lateinit var sumMeta: TextView
    private lateinit var sumOut: TextView
    private val hits = ArrayList<JSONObject>()
    private lateinit var hitAdapter: HitAdapter
    private var searchReqId = ""
    private var sumReqId = ""
    private var sumBusy = false
    private var sumSecs = 0
    private var flashId = ""
    /** 输入停顿 350ms 才真发（和桌面端同一个数），不然每个字敲一次网关、扫一次分区。
     *  这里必须用静态那颗 Looper：属性初始化跑在 Activity 的构造函数里，那时 base context 还没挂上，
     *  `mainLooper` 会 NPE（实测：一开会话页整个进程崩掉，回桌面） */
    private val searchDelay = android.os.Handler(android.os.Looper.getMainLooper())
    private val runSearchNow = Runnable { runMsgSearch() }
    /** 归纳在这台机器上实测要 30~66 秒，按钮按下去不给个数就像界面冻住了，所以自己走一秒一跳 */
    private val sumTick = object : Runnable {
        override fun run() {
            if (!sumBusy) return
            sumSecs++
            sumBtn.text = "归纳中… ${sumSecs}s"
            searchDelay.postDelayed(this, 1000)
        }
    }

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
        setupSearch()

        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<Button>(R.id.send)
        /* 「发送」常驻右边：没字时不可选中（底色换成 brand 与白各 50% 那颗），有字才变实蓝、才给点 */
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
            val rid = ws?.send("MESSAGE_SEND", JSONObject()
                .put("conversationId", convId)
                .put("messageType", "TEXT")
                .put("content", text)
                .put("extra", ""), nextReqId())
            /* 网关不会把消息回推给发送方（实测：发完 LOAD_MESSAGES 里才出现它），
               所以自己这条要当场摆上，否则"发出去了但界面上没有"。和桌面端同一条规则 */
            val local = JSONObject()
                .put("conversationId", convId)
                .put("senderId", myId)
                .put("messageType", "TEXT")
                .put("content", text)
                .put("createTime", OffsetDateTime.now(java.time.ZoneOffset.UTC).toString())
            trackSend(rid, local)
            msgs.add(local)
            rebuildRows(); adapter.notifyDataSetChanged()
            list.scrollToPosition(rows.size - 1)
            input.setText("")
        }
        setupPanels(input)

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

        val w = Ws(Cfg.wsBase(this), Cfg.token(this), Cfg.deviceId(this),
            onFrame = { onFrame(it) },
            onState = { runOnUiThread { state.text = it } },
            // 首连和每次重连回来都补拉一次：断线期间别人发的那几条，只能靠拉回来
            onReady = { ws?.send("LOAD_MESSAGES", JSONObject().put("conversationId", convId).put("limit", 50)) },
            refresh = { Cfg.renewToken(this) })
        ws = w
        w.open()
    }

    /** 面板开的是哪块：0=没开、1=表情、2=文件。同一时刻只开一块 */
    private var panelKind = 0
    private lateinit var panel: View
    private lateinit var emojiScroll: PageScroller
    private lateinit var plusGrid: GridLayout
    private lateinit var inputView: EditText

    private fun setupPanels(input: EditText) {
        panel = findViewById(R.id.panel)
        emojiScroll = findViewById(R.id.emojiScroll)
        plusGrid = findViewById(R.id.plusGrid)
        inputView = input
        buildEmojis(input)
        buildPlus()
        findViewById<View>(R.id.emojiBtn).setOnClickListener { showPanel(if (panelKind == 1) 0 else 1) }
        findViewById<View>(R.id.plusBtn).setOnClickListener { showPanel(if (panelKind == 2) 0 else 2) }
        // 工具栏第二颗是相册的快捷入口，走的是 ＋ 面板里「相册」同一条路
        findViewById<View>(R.id.imageBtn).setOnClickListener { pick("image/*", REQ_ALBUM) }
        /* 键盘和面板互斥。
           面板→键盘：showPanel 里 hideKeyboard()。
           键盘→面板：手指按在输入框上那一刻（ACTION_DOWN）先把面板收掉，键盘交给系统自己起来。
           试过另外两种，都不行：① OnClickListener —— 挂在 EditText 上实测不一定触发（点一下只是
           放光标），结果键盘起来了面板没收；② 量内容区高度收缩判键盘 —— 收面板会改布局、
           布局一改 MIUI 的键盘又缩回去，两个动作互为因果，最后面板关了键盘也没留住。
           按下的顺序没有环：面板先没，键盘再起。 */
        input.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN && panelKind != 0) showPanel(0)
            false
        }
    }

    /** ＋ 面板八颗格子照图全摆。真接得通的只有两颗：相册（挑图）和文件（挑任意文件），
     *  都走系统选择器再上传；其余六颗后端没有对应消息类型，画淡 0.4，
     *  和长按菜单"十项全摆八项灰"是同一口径。
     *
     *  「视频通话」这一格单独一档：通话信令（CALL_INVITE）后端是有的、桌面端也打得通，
     *  缺的是手机端这侧 —— 没有 LiveKit 媒体面，接起来双方没画面没声音。
     *  所以它画淡但**给点**，点了把这句实话讲出来，而不是静默不响应：
     *  灰掉不给点是"这能力不存在"，点了回一句是"这能力有、这端还没接"，两件事不一样。
     *  相册没丢：输入框上方工具栏第二颗就是它的快捷入口。 */
    private fun buildPlus() {
        val px = resources.displayMetrics.density
        val tiles = listOf(
            "视频通话" to R.drawable.ic_video_call, "拍摄" to R.drawable.ic_camera,
            "位置" to R.drawable.ic_pin, "语音输入" to R.drawable.ic_mic,
            "收藏" to R.drawable.ic_cube, "个人名片" to R.drawable.ic_person,
            "文件" to R.drawable.ic_wb_folder, "音乐" to R.drawable.ic_music)
        val real = mapOf(
            "文件" to { pick("*/*", REQ_FILE) })
        tiles.forEach { (name, icon) ->
            val cell = layoutInflater.inflate(R.layout.tile_pick, plusGrid, false)
            cell.findViewById<ImageView>(R.id.tic).setImageResource(icon)
            cell.findViewById<TextView>(R.id.tname).text = name
            val act = real[name]
            if (act != null) {
                cell.isClickable = true
                cell.setOnClickListener { act() }
            } else {
                cell.alpha = 0.4f
                if (name == "视频通话") {
                    cell.isClickable = true
                    cell.setOnClickListener {
                        shout("手机端还没接通话通道（没有音视频那一层），先在桌面客户端打这通")
                    }
                } else cell.isClickable = false
            }
            plusGrid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                height = (96 * px).toInt()
            })
        }
    }

    private fun showPanel(kind: Int) {
        panelKind = kind
        panel.visibility = if (kind == 0) View.GONE else View.VISIBLE
        emojiScroll.visibility = if (kind == 1) View.VISIBLE else View.GONE
        plusGrid.visibility = if (kind == 2) View.VISIBLE else View.GONE
        dismissTip()
        // 开面板就把键盘收下去，不然两个一起占着下半屏
        if (kind != 0) hideKeyboard()
    }

    private fun hideKeyboard() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(inputView.windowToken, 0)
    }

    private fun hideKeyboard(v: View) {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** 检索这一摊的接线。入口是顶栏那颗放大镜（不藏在长按菜单的「搜一搜」里），
     *  面板里做的事只有两件：跳回命中的那条、把这批交给模型归纳一句。 */
    private fun setupSearch() {
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchPanel = findViewById(R.id.searchPanel)
        searchMeta = findViewById(R.id.searchMeta)
        searchNote = findViewById(R.id.searchNote)
        searchList = findViewById(R.id.searchList)
        sumRow = findViewById(R.id.sumRow)
        sumBtn = findViewById(R.id.sumBtn)
        sumMeta = findViewById(R.id.sumMeta)
        sumOut = findViewById(R.id.sumOut)
        hitAdapter = HitAdapter()
        searchList.layoutManager = LinearLayoutManager(this)
        searchList.adapter = hitAdapter

        findViewById<View>(R.id.searchBtn).setOnClickListener {
            if (searchBar.visibility == View.VISIBLE) closeSearch() else openSearch()
        }
        findViewById<View>(R.id.searchCancel).setOnClickListener { closeSearch() }
        findViewById<View>(R.id.searchClear).setOnClickListener { searchInput.setText("") }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onSearchText()
        })
        // 键盘上那颗「搜索」不等 350ms，立刻发
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            searchDelay.removeCallbacks(runSearchNow); runMsgSearch(); true
        }
        sumBtn.setOnClickListener { runMsgSummary() }
        // ✕ 的初始态：这会儿还没字，画淡、不给点。onSearchText 只在字变了以后才跑，得先给一次
        findViewById<View>(R.id.searchClear).alpha = 0.4f
        findViewById<View>(R.id.searchClear).isClickable = false
    }

    private fun onSearchText() {
        val has = searchInput.text.toString().trim().isNotEmpty()
        // 清空那颗没字时画淡、不给点：格子一直占着，这一行不抖（工具栏那六颗同一口径）
        val clear = findViewById<View>(R.id.searchClear)
        clear.alpha = if (has) 1f else 0.4f
        clear.isClickable = has
        searchDelay.removeCallbacks(runSearchNow)
        if (!has) { clearResults(); return }
        searchMeta.text = "检索中…"
        searchNote.visibility = View.GONE
        searchPanel.visibility = View.VISIBLE
        hits.clear(); hitAdapter.notifyDataSetChanged()
        hideSum()
        searchDelay.postDelayed(runSearchNow, 350)
    }

    private fun openSearch() {
        // 表情/＋ 那块面板先收下去：它和键盘抢的是同半屏
        if (panelKind != 0) showPanel(0)
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchDelay.removeCallbacks(runSearchNow)
        stopSum()
        searchBar.visibility = View.GONE
        searchInput.setText("")
        clearResults()
        searchPanel.visibility = View.GONE
        hideKeyboard(searchInput)
    }

    private fun clearResults() {
        hits.clear(); hitAdapter.notifyDataSetChanged()
        searchMeta.text = ""; searchNote.visibility = View.GONE
        searchPanel.visibility = View.GONE
        hideSum()
        flashId = ""
    }

    private fun runMsgSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        searchReqId = nextReqId()
        val rid = ws?.send("SEARCH_MESSAGES", JSONObject()
            .put("conversationId", convId)
            .put("keyword", q)
            .put("limit", 50)
            .put("days", 30), searchReqId)
        // 发不出去就说发不出去，别让面板挂着"检索中…"演成在跑
        if (rid == null) {
            searchMeta.text = ""
            searchNote.text = "连接断了，检索没发出去"
            searchNote.visibility = View.VISIBLE
            searchPanel.visibility = View.VISIBLE
        }
    }

    /** 后端报的实数摆在最上面：命中几条、翻了多少条、多久、用的哪个后端。
     *  days 不在响应里（网关把上限夹在 30），所以那句提示里的天数用发出去的那个值 */
    private fun metaLine(d: JSONObject, n: Int): String {
        val bits = ArrayList<String>()
        bits.add("命中 $n 条")
        bits.add("翻了 ${d.optInt("scanned")} 条")
        bits.add("${d.optLong("tookMs")}ms")
        bits.add("后端 ${d.optString("provider")}")
        return bits.joinToString(" · ")
    }

    /** 点一条命中：滚到消息区那一条并闪一圈。命中的那条不在本机拉到的窗口里就直说，
     *  不偷偷改成"搜不到"——这一页只 LOAD_MESSAGES 50 条，往上没有翻页 */
    private fun jumpToHit(h: JSONObject) {
        val mid = h.optString("messageId")
        val msg = msgs.firstOrNull { (it.opt("messageId") ?: it.opt("id"))?.toString() == mid }
        if (msg == null) {
            shout("这条不在本机已加载的 50 条里，跳不过去")
            return
        }
        val pos = rows.indexOfFirst { it is MsgRow && it.m === msg }
        if (pos < 0) return
        flashId = mid
        adapter.notifyDataSetChanged()
        /* 键盘先收下去：不收的话消息区只剩面板底下一小条，跳过来的那条会藏在那一小条外面。
           只 hide 不够——搜索框还持有焦点，MIUI 会把键盘再顶回来（实测这张图里键盘又在），
           所以先把焦点交出去。定位用「钉在消息区顶边」（offset 0），不用屏幕高度的三分之一——
           实测按屏幕算会把带光圈的那一条顶到检索面板底下去，屏幕上根本看不见（上一版就是这个样子）。
           面板留着不关：还要接着点第二条。 */
        searchInput.clearFocus()
        hideKeyboard(searchInput)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        searchDelay.postDelayed({
            if (flashId == mid && !isFinishing) { flashId = ""; adapter.notifyDataSetChanged() }
        }, 1800)
    }

    /* 归纳这一批：客户端只交 (messageId, messageDate)，原文由消息服务自己从库里读——
       不然这颗按钮就成了往模型提示词里塞话的入口。取前 8 条，和桌面端同一个数。 */
    private fun runMsgSummary() {
        if (hits.isEmpty() || sumBusy) return
        val items = org.json.JSONArray()
        for (i in 0 until minOf(8, hits.size)) {
            val h = hits[i]
            items.put(JSONObject()
                .put("id", h.optString("messageId"))
                .put("date", h.optString("messageDate")))
        }
        sumReqId = nextReqId()
        sumBusy = true; sumSecs = 0
        sumBtn.isEnabled = false; sumBtn.alpha = 0.4f; sumBtn.text = "归纳中… 0s"
        sumOut.visibility = View.GONE; sumMeta.text = ""
        searchDelay.postDelayed(sumTick, 1000)
        val rid = ws?.send("SEARCH_SUMMARY", JSONObject()
            .put("conversationId", convId)
            .put("keyword", searchInput.text.toString().trim())
            .put("items", items), sumReqId)
        if (rid == null) { stopSum(); sumResult("连接断了，归纳没发出去", true) }
    }

    private fun stopSum() {
        searchDelay.removeCallbacks(sumTick)
        sumBusy = false
        sumBtn.isEnabled = true; sumBtn.alpha = 1f; sumBtn.text = "AI 归纳这批"
    }

    private fun hideSum() {
        stopSum()
        sumRow.visibility = View.GONE
        sumOut.visibility = View.GONE
        sumOut.text = ""
    }

    private fun sumResult(text: String, bad: Boolean) {
        sumOut.text = text
        sumOut.setTextColor(getColor(if (bad) R.color.warn else R.color.ink))
        sumOut.visibility = View.VISIBLE
    }

    /** 网关有时把 data 塞成字符串（桌面端也是两种都认） */
    private fun dataOf(m: JSONObject): JSONObject? = when (val raw = m.opt("data")) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    }

    /** org.json 的 optString 会把 JSON 的 null 读成字符串 "null"（实测：归纳失败那支
     *  后端回 summary:null + error:"模型服务没答上…"，界面上就印了两个字母 null 当成结论）。
     *  所以判"有没有"必须走 isNull，不能判空串。 */
    private fun str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k)

    /** 表情排成轮播：一页 4 行 × 7 列 = 28 个，页宽等于视口宽，装不下的往左右翻，松手吸附到整页。
     *  点一下往输入框接一个字，长按那一格出含义气泡。
     *  不满一行时右边补空占位——不补的话那一行的格子会被 weight 拉伸，格心就和满行对不上。 */
    private fun buildEmojis(input: EditText) {
        val pages = findViewById<LinearLayout>(R.id.emojiPages)
        val px = resources.displayMetrics.density
        val cell = (52 * px).toInt()
        EMOJI.chunked(4 * COLS).forEach { chunk ->
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((6 * px).toInt(), 0, (6 * px).toInt(), 0)
            }
            chunk.chunked(COLS).forEach { rowItems ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowItems.forEach { (cp, meaning) ->
                    val glyph = String(Character.toChars(cp))
                    val b = TextView(this)
                    b.text = glyph
                    b.textSize = 22f
                    b.gravity = Gravity.CENTER
                    b.isClickable = true
                    b.setBackgroundResource(R.drawable.bg_cell)
                    b.setOnClickListener { input.append(glyph) }
                    b.setOnLongClickListener { showTip(b, glyph, meaning); true }
                    row.addView(b, LinearLayout.LayoutParams(0, cell, 1f))
                }
                repeat(COLS - rowItems.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, cell, 1f))
                }
                page.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            pages.addView(page, LinearLayout.LayoutParams(
                resources.displayMetrics.widthPixels, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        emojiScroll.pageCount = pages.childCount
    }

    /** 表情含义的气泡。不用系统那套 tooltip——这台机（M2012K10C / MIUI）长按压根不出它，
     *  截屏差分只看到状态栏时钟在动。不抓焦点也不接触摸：它就是个标签，
     *  挡不住底下那一排的点，1.6 秒自己收。
     *  底下带一个三角指着被按那一格：气泡本身会被屏幕左右边缘夹住，所以三角的位置单独算，
     *  夹到哪都还指着那一格；同时那一格上"行按下色"的高亮，跟着气泡一起收。 */
    private var tip: PopupWindow? = null
    private var tipCell: View? = null

    private fun showTip(cell: View, glyph: String, meaning: String) {
        dismissTip()
        tipCell = cell
        cell.isSelected = true
        val v = layoutInflater.inflate(R.layout.bubble_tip, null)
        v.findViewById<TextView>(R.id.tipGlyph).text = glyph
        v.findViewById<TextView>(R.id.tipText).text = meaning
        val tri = v.findViewById<View>(R.id.tipTri)
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        tri.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val tw = v.measuredWidth
        val th = v.measuredHeight
        val dp = resources.displayMetrics.density
        val gap = (2 * dp).toInt()
        val at = IntArray(2); cell.getLocationOnScreen(at)
        val win = IntArray(2); window.decorView.getLocationOnScreen(win)
        val cellCx = at[0] + cell.width / 2 - win[0]
        val x = (cellCx - tw / 2).coerceIn(0, resources.displayMetrics.widthPixels - tw)
        // 头顶放不下（第一行贴着面板顶）就翻到脚下
        val y = if (at[1] - win[1] - th - gap >= 0) at[1] - win[1] - th - gap
                else at[1] - win[1] + cell.height + gap
        val edge = (11 * dp).toInt()
        (tri.layoutParams as android.view.ViewGroup.MarginLayoutParams).marginStart =
            (cellCx - tri.measuredWidth / 2 - x).coerceIn(edge, tw - tri.measuredWidth - edge)
        tip = PopupWindow(v, tw, th, false).apply {
            isTouchable = false
            showAtLocation(cell, Gravity.NO_GRAVITY, x, y)
        }
        android.os.Handler(mainLooper).postDelayed({ dismissTip() }, 1600)
    }

    private fun dismissTip() {
        tip?.dismiss(); tip = null
        tipCell?.isSelected = false; tipCell = null
    }

    private fun pick(mime: String, req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
        }
        runCatching { startActivityForResult(i, req) }
            .onFailure { shout("这台机没有可用的选择器：${it.message}") }
    }

    @Deprecated("Activity 的 onActivityResult，选择器只这一个入口，够用")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri = data?.data ?: return
        sendPicked(uri)
    }

    /** 选择器给的是 content:// ：名字要查 OpenableColumns，字节整个读进内存再 multipart 传。
     *  后端限 20MB，超了回 413，那句在这边翻成人话。 */
    private fun sendPicked(uri: android.net.Uri) {
        val name = displayName(uri).ifEmpty { "文件" }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        state.text = "上传中…"
        thread(name = "upload") {
            val res = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("读不到这个文件")
                Api(Cfg.msgBase(this), Cfg.token(this)).uploadFile(bytes, name, mime)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                res.onSuccess { sendMedia(it) }
                    .onFailure {
                        state.text = ""
                        shout(if (it.message?.startsWith("413") == true) "文件超过 20MB"
                              else "上传失败：${it.message}")
                    }
            }
        }
    }

    private fun displayName(uri: android.net.Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0).orEmpty() }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /** 发一条文件/图片消息：内容存 JSON 引用、类型按 contentType 分 IMAGE/FILE，
     *  和桌面端 sendMediaFile 同一形状。自己这条照样当场摆上（网关不回推给发送方） */
    private fun sendMedia(ref: Api.FileRef) {
        val rid = ws?.send("MESSAGE_SEND", JSONObject()
            .put("conversationId", convId)
            .put("messageType", ref.type())
            .put("content", ref.content())
            .put("extra", ""), nextReqId())
        val local = JSONObject()
            .put("conversationId", convId)
            .put("senderId", myId)
            .put("messageType", ref.type())
            .put("content", ref.content())
            .put("createTime", OffsetDateTime.now(java.time.ZoneOffset.UTC).toString())
        trackSend(rid, local)
        msgs.add(local)
        rebuildRows(); adapter.notifyDataSetChanged()
        list.scrollToPosition(rows.size - 1)
        state.text = ""
        showPanel(0)
    }

    /** 我发出去、还没等到回执的那几行：requestId -> 界面上这一条 */
    private val pendingRows = HashMap<String, JSONObject>()
    private var reqSeq = 0

    private fun nextReqId(): String {
        reqSeq++
        return "m$reqSeq-" + System.currentTimeMillis()
    }

    /**
     * 挂上"发送中"：rid 为 null 说明连 socket 都没有（或写不出去），那就是没发出去，
     * 不能挂着"发送中"骗人。messageId 先占一个本地值，ACK 到了换成服务端那个 timeuuid。
     */
    private fun trackSend(rid: String?, row: JSONObject) {
        if (rid == null) {
            row.put("messageId", "lost-" + System.nanoTime())
            row.put("status", "FAILED")
        } else {
            row.put("messageId", rid)
            row.put("status", "PENDING")
            pendingRows[rid] = row
        }
    }

    /** 按 messageId 改这几行的状态，改动了才重画 */
    private fun markStatus(ids: Set<String>, from: String, to: String) {
        if (ids.isEmpty()) return
        var hit = false
        for (row in msgs) {
            if (row.optString("messageId") in ids && row.optString("status") == from) {
                row.put("status", to); hit = true
            }
        }
        if (hit) runOnUiThread { adapter.notifyDataSetChanged() }
    }

    private fun shout(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
                val split = ConvRows.split(arr, convId)
                msgs.clear(); msgs.addAll(split.kept.reversed())
                rebuildRows(); adapter.notifyDataSetChanged()
                /* 状态行是这一页"我到底装了什么"的唯一出口：外来行被丢掉这件事必须在这行上看得见，
                   不然页面变空时没人分得清是"没消息"还是"被过滤了" */
                state.text = if (split.dropped == 0) "${msgs.size} 条消息"
                             else "${msgs.size} 条消息 · 另有 ${split.dropped} 条不是本会话的，已丢掉"
                if (rows.isNotEmpty()) list.scrollToPosition(rows.size - 1)
            }

            "MESSAGE_RECEIVE" -> {
                val d = m.optJSONObject("data") ?: return
                Delivery.echo(ws, m, myId)
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
                /* ACK=SENT 说的是"服务器收下了这条"，不是"对方收到了"——原先这里写成"已送达"是在说谎。
                   送达回执是另一条帧（MESSAGE_DELIVERED），本机两边都处理：自己发的那条会亮"已送达"，
                   收到的那条由 Delivery.echo 替对方点起同一颗。 */
                val ok = d.optString("status") == "SENT"
                state.text = if (ok) "已发送" else "发送失败：${d.optString("status")}"
                val row = pendingRows.remove(m.optString("requestId"))
                if (row != null) {
                    if (ok) {
                        val mid = d.optString("messageId")
                        if (mid.isNotEmpty()) row.put("messageId", mid)
                        row.put("status", "SENT")
                    } else {
                        row.put("status", "FAILED")
                    }
                    adapter.notifyDataSetChanged()
                }
            }

            "MESSAGE_DELIVERED" -> {
                // 只有当前这条会话的回执才认：列表里同时开着好几条时会串
                val d = m.optJSONObject("data") ?: return
                if (d.optString("conversationId") != convId) return
                val arr = d.optJSONArray("messageIds") ?: return
                val ids = HashSet<String>()
                for (i in 0 until arr.length()) ids.add(arr.optString(i))
                markStatus(ids, "SENT", "DELIVERED")
            }

            "SEARCH_RESULT" -> {
                if (m.optString("requestId") != searchReqId) return
                val d = dataOf(m) ?: return
                val err = str(d, "error")
                if (err.isNotEmpty()) {
                    clearResults()
                    searchNote.text = err
                    searchNote.visibility = View.VISIBLE
                    searchPanel.visibility = View.VISIBLE
                    return
                }
                val arr = d.optJSONArray("hits") ?: org.json.JSONArray()
                hits.clear()
                for (i in 0 until arr.length()) hits.add(arr.getJSONObject(i))
                hitAdapter.notifyDataSetChanged()
                searchMeta.text = metaLine(d, hits.size)
                searchNote.text = when {
                    d.optBoolean("truncated") -> "只翻了最近 30 天，更早的没查"
                    hits.isEmpty() -> "这条会话里没搜到，换个词试试"
                    else -> ""
                }
                searchNote.visibility = if (searchNote.text.isEmpty()) View.GONE else View.VISIBLE
                searchPanel.visibility = View.VISIBLE
                hideSum()
                if (hits.isNotEmpty()) sumRow.visibility = View.VISIBLE
            }

            "SEARCH_SUMMARY_RESULT" -> {
                if (m.optString("requestId") != sumReqId) return
                stopSum()
                val d = dataOf(m) ?: return
                val sum = str(d, "summary")
                val err = str(d, "error")
                when {
                    sum.isNotEmpty() -> {
                        sumResult(sum, false)
                        sumMeta.text = "${str(d, "provider")} · ${d.optLong("tookMs")}ms"
                    }
                    // 没结论就不标模型和耗时：那两颗是"这次归纳是谁跑的、跑了多久"的凭据，不是错误的一部分
                    err.isNotEmpty() -> { sumMeta.text = ""; sumResult(err, true) }
                    else -> { sumMeta.text = ""; sumResult("模型没给结论", true) }
                }
            }
        }
    }

    /** 界面上的一行：一条消息，或一条居中时间分隔 */
    private sealed interface Row
    private data class MsgRow(val m: JSONObject) : Row
    private data class TimeRow(val text: String) : Row
    /** call=true 的那几句是通话话单（通话时长 / 未接听 / 已拒绝），左边多一颗电话听筒，
     *  和桌面端那颗胶囊同一副样子；判据用整句等值而不是"包含"——
     *  "已拒绝"这三个字远程控制那一路也在写（拒绝了远程控制），用包含会把控制那句也画成电话。 */
    private data class NoticeRow(val text: String, val call: Boolean = false) : Row

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
            /* 通话留下的那句是会话里的一条 SYSTEM 消息：居中小灰药丸，
               不进气泡、不带头像、也不给长按弹框（它不是能撤回/引用的东西）。
               话单那几句左边多一颗电话听筒，其余系统提示不带。 */
            if (m.optString("messageType") == "SYSTEM") {
                val c = m.optString("content")
                rows.add(NoticeRow(c, isCallLog(c)))
            } else rows.add(MsgRow(m))
        }
    }

    /** 和桌面端 CALL_LOG_TEXT 同一条判据：整句等值，不用"包含" */
    private fun isCallLog(text: String): Boolean =
        Regex("""^通话时长 \d{2}:\d{2}$""").matches(text.trim()) ||
            text.trim() == "未接听" || text.trim() == "已拒绝"

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
        override fun getItemViewType(p: Int) = when (rows[p]) {
            is TimeRow -> 1
            is NoticeRow -> 2
            else -> 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
            when (type) {
                1 -> TimeVH(layoutInflater.inflate(R.layout.item_time, parent, false))
                2 -> NoticeVH(layoutInflater.inflate(R.layout.item_notice, parent, false))
                else -> VH(layoutInflater.inflate(R.layout.item_message, parent, false))
            }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            when (val r = rows[i]) {
                is TimeRow -> (h as TimeVH).at.text = r.text
                is NoticeRow -> {
                    val h2 = h as NoticeVH
                    h2.nt.text = r.text
                    // 不是话单就把图标 gone 掉：它不占位，药丸宽度和居中节奏和加图标之前完全一致
                    h2.tic.visibility = if (r.call) View.VISIBLE else View.GONE
                }
                is MsgRow -> bindMsg(h as VH, r.m)
            }
        }
    }

    /** 命中列表的一行：上面"几点 · 谁"，下面原文预览。点一下跳回消息区。 */
    private inner class HitAdapter : RecyclerView.Adapter<HitVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): HitVH =
            HitVH(layoutInflater.inflate(R.layout.item_search_hit, parent, false))

        override fun getItemCount(): Int = hits.size

        override fun onBindViewHolder(h: HitVH, i: Int) {
            val m = hits[i]
            h.who.text = "${Times.conv(m.optString("createTime"))} · ${hitSender(m)}"
            val raw = m.optString("content")
            h.txt.text = Api.fileRef(raw)?.preview() ?: raw
            h.itemView.isClickable = true
            h.itemView.setOnClickListener { jumpToHit(m) }
        }
    }

    /** 命中行的发送人：自己那条写"我"，别人的先查好友表，查不到再去补（和气泡同一条路） */
    private fun hitSender(m: JSONObject): String {
        val id = m.opt("senderId")?.toString() ?: ""
        if (id.isEmpty()) return "系统"
        if (id == myId) return "我"
        return names[id]?.takeIf { it.isNotEmpty() } ?: nameOf(id).ifEmpty { "用户${id.takeLast(4)}" }
    }

    private fun bindMsg(h: VH, m: JSONObject) {
        // senderId 是 JSON 数字，getString 会抛；一律 get().toString()
        val sender = m.opt("senderId")?.toString() ?: ""
        val self = sender.isNotEmpty() && sender == myId
        /* 文件/图片消息的内容是一串 JSON 引用，直接印出来就是一屏大括号——
           按桌面端 previewOf 那句改成 [图片] xxx.png / [文件] 报告.pdf */
        val raw = m.optString("content")
        h.bubble.text = Api.fileRef(raw)?.preview() ?: raw
        /* 检索命中跳过来的那一条闪一圈：只加一圈颜色，气泡本身的形状、位置、字号一个字都不动
           （桌面端 .msg.hit 也是 box-shadow，不参与布局） */
        val flashed = flashId.isNotEmpty() && mId(m) == flashId
        h.bubble.setBackgroundResource(when {
            flashed && self -> R.drawable.bubble_flash_self
            flashed -> R.drawable.bubble_flash_other
            self -> R.drawable.bubble_self
            else -> R.drawable.bubble_other
        })
        h.bubble.setTextColor(if (self) 0xFFFFFFFF.toInt() else getColor(R.color.ink))
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
        val tail = if (self) getColor(R.color.brand) else getColor(R.color.nb_bg_3)
        h.tailL.setColorFilter(tail, android.graphics.PorterDuff.Mode.SRC_IN)
        h.tailR.setColorFilter(tail, android.graphics.PorterDuff.Mode.SRC_IN)
        h.who.text = who
        h.who.visibility = if (who.isEmpty()) View.GONE else View.VISIBLE
        /* 名字对齐的是气泡那个方块，方块外侧还压着 6dp 宽的尾巴，所以让开一个尾巴的宽度。
           以前那个 60dp 是给「整行之上」那种排法让头像的，名字进了列里就不需要了 */
        val tailW = (6 * resources.displayMetrics.density).toInt()
        h.who.setPadding(if (self) 0 else tailW, 0, if (self) tailW else 0, 0)
        /* 自己这一条的状态字：发送中 / 已送达 / 没发出去。
           从历史里翻出来的条没有 status 键，所以一律不显示（回执不落库，和桌面端同一口径）。
           "已发送"不给字：它和"没有任何字"是同一件事，多一颗反而抢戏（桌面端也是这么定的）。
           已读不做：两端现在都没有发 MESSAGE_READ 的一方，加了就是一颗永远不亮的字。 */
        val stTxt = if (self) when (m.optString("status")) {
            "PENDING" -> "发送中"
            "DELIVERED" -> "已送达"
            "FAILED" -> "没发出去"
            else -> ""
        } else ""
        h.st.text = stTxt
        h.st.visibility = if (stTxt.isEmpty()) View.GONE else View.VISIBLE
        h.st.setTextColor(getColor(if (stTxt == "没发出去") R.color.danger else R.color.ink_dim2))
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

    /** 长按一条消息 → 桌面端右键那几项，这里改成五列一行的格子（微信那种双行排法）。
     *  真能做的只有「复制」和「撤回」，其余按桌面端同样的口径画淡、点了不响应。
     *  弹的位置自己算：底下放不下就翻到上面，并把那颗三角挪到正对着这条消息的气泡。
     *  V3.0：增加「远程控制」—— 单聊才亮（群聊没这能力），后端控制面能力有了就启用 */
    private fun showMsgMenu(anchor: View, m: JSONObject, self: Boolean) {
        val isText = (m.optString("messageType").ifEmpty { "TEXT" }) == "TEXT"
        val canRevoke = self && !mId(m).startsWith("local-") && mId(m).isNotEmpty() &&
            System.currentTimeMillis() - tsOf(m) <= REVOKE_MS
        // 远程控制的入口：信令和话单后端都通了，缺的是手机端这一侧的状态条，所以画淡 + 点了回一句实话。
        // 群聊没有"那一个人"可以控制，这一档连点都不给（那句"只有单聊能发起"是另一回事）。
        val ctlTip = if (isGroup) "只有单聊能发起远程控制"
                     else "手机端还没做远程控制的状态条，先在桌面客户端发起这一场"
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
            MItem("撤回", R.drawable.ic_m_undo, canRevoke) { confirmRevoke(m) },
            MItem("远程控制", R.drawable.ic_remote_control, false, ctlTip)
        )
        val root = layoutInflater.inflate(R.layout.popup_msg_menu, null) as FrameLayout
        val grid = root.findViewById<GridLayout>(R.id.grid)
        items.forEach { it0 ->
            val cell = layoutInflater.inflate(R.layout.item_msg_menu, grid, false)
            cell.findViewById<ImageView>(R.id.ic).setImageResource(it0.icon)
            cell.findViewById<TextView>(R.id.name).text = it0.name
            cell.alpha = if (it0.on) 1f else 0.4f
            when {
                it0.on -> { cell.isClickable = true; cell.setOnClickListener { pop?.dismiss(); it0.run() } }
                it0.tip.isNotEmpty() -> {
                    cell.isClickable = true
                    cell.setOnClickListener { pop?.dismiss(); shout(it0.tip) }
                }
                else -> cell.isClickable = false
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            })
        }
        val p = PopupWindow(root,
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.isOutsideTouchable = true
        pop = p
        p.setOnDismissListener { pop = null }
        /* 格子是五列 columnWeight 均分，用 WRAP_CONTENT / UNSPECIFIED 去量会得到宽度 0 ——
           弹出来就是一个看不见的窗（上一版把 p.width 这行丢了，症状正是"长按没反应"）。
           所以定宽 5×62dp，量高度也按这个宽去量。 */
        val d = resources.displayMetrics
        val w = (62 * 5 * d.density).toInt()
        p.width = w
        /* 三角往卡片里压进去的量：只要盖住那 1px 描边就够了，多压会把斜边扎进卡片白面 */
        val ov = (1 * d.density).toInt()
        val tipH = (6 * d.density).toInt()
        grid.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val total = grid.measuredHeight + tipH - ov

        /* 判上下用**窗口内**坐标（和 decorView 的宽高同一套），
           但 showAtLocation 吃的是**屏幕**坐标 —— 应用窗口顶在状态栏下面，
           直接把窗口坐标喂给它会整体上移一个状态栏的高度，所以最后加窗口在屏幕上的偏移。 */
        val a = IntArray(2); anchor.getLocationInWindow(a)
        val winH = window.decorView.height
        val winW = window.decorView.width
        /* 横向对齐气泡**靠头像那一端**：别人发的贴它的左沿，我发的贴它的右沿。
           （之前按整屏居中，卡片和消息各说各话。）
           纵向缝取 0：三角的尖**就落在气泡的那条边上**，既不压进气泡，也不空出一道。 */
        val pl = MsgMenuPlace.place(winW, winH, a[0], a[1], anchor.width, anchor.height,
            self, w, total, 0, (12 * d.density).toInt(),
            (8 * d.density).toInt(), (12 * d.density).toInt())

        val tip = root.findViewById<ImageView>(if (pl.flipUp) R.id.tipDown else R.id.tipUp)
        tip.visibility = View.VISIBLE
        /* FrameLayout 按子节点顺序画，三角排在卡片后面 → 压住卡片那条描边，接缝看不出来。
           尖朝上时卡片整体下让 tipH-ov，让三角的底扎进卡片 1px；尖朝下时卡片在顶上，
           三角的顶（那条宽边）扎进卡片底边。 */
        (grid.layoutParams as FrameLayout.LayoutParams).topMargin =
            if (pl.flipUp) 0 else tipH - ov
        (tip.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = pl.tipMargin
            topMargin = if (pl.flipUp) grid.measuredHeight - ov else 0
        }
        val o = IntArray(2); window.decorView.getLocationOnScreen(o)
        /* pl.x/pl.y 就是弹框最外层的左上角：尖朝上时 y 即尖所在的那条线，正好落在气泡底边上；
           卡片在 y 下面 tipH-ov（三角占的那段，两处重叠 ov）。 */
        val px = (o[0] + pl.x).coerceIn(0, (d.widthPixels - w).coerceAtLeast(0))
        val py = (o[1] + pl.y).coerceIn(0, (d.heightPixels - total).coerceAtLeast(0))
        p.showAtLocation(window.decorView, Gravity.NO_GRAVITY, px, py)
    }

    /** on=false 就是画淡。再带一句 tip 的，画淡但**给点**，点了把这句实话讲出来：
     *  "灰掉不给点"说的是这能力不存在，"点了回一句"说的是这能力有、这端还没接 —— 两件事不一样。 */
    private data class MItem(val name: String, val icon: Int, val on: Boolean,
                             val tip: String = "", val run: () -> Unit = {})

    /** 服务端回来的这条消息的 id：本地回显的那份是 local- 开头，没有可撤回的对象 */
    private fun mId(m: JSONObject): String =
        (m.opt("messageId") ?: m.opt("id"))?.toString() ?: ""

    /** 远程控制这一格不走这里发信令：网关的 CONTROL_REQUEST 和话单落库都通了（桌面端能发起、能应答），
     *  缺的是手机端这一侧的状态条 —— 发出去本机没地方显示"等待对方同意/你正在控制对方"，
     *  也接不了对方发来的那一场。所以长按那颗画淡、点了回一句实话（见 MItem.tip）。
     *  真要发是 ws.send 一个 CONTROL_REQUEST 帧，和桌面端 useControl 同一条路；
     *  chat-user 上那组 control 接口是话单落库那侧的，拿它当发起入口是错的。 */

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
    /** 这条会话正开在眼前：后台服务据此不再给它弹通知，退出这页就恢复。
     *  同一进程内可见，所以放伴生对象，不另设广播。 */
    override fun onResume() {
        super.onResume()
        liveConv = convId
        Notify.cancel(this, convId)
    }

    override fun onPause() {
        super.onPause()
        if (liveConv == convId) liveConv = null
        /* 在这页看过的消息服务端已经清过未读，退出去让角标和汇总那颗跟着新 */
        NotifyService.refresh(this)
    }

    override fun onDestroy() {
        // 检索的延时发、归纳的秒表都挂在这个 Handler 上，页面没了就不能再往回砸
        searchDelay.removeCallbacksAndMessages(null)
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
        val st: TextView = v.findViewById(R.id.st)
    }

    private class TimeVH(v: View) : RecyclerView.ViewHolder(v) {
        val at: TextView = v.findViewById(R.id.at)
    }

    private class HitVH(v: View) : RecyclerView.ViewHolder(v) {
        val who: TextView = v.findViewById(R.id.hitWho)
        val txt: TextView = v.findViewById(R.id.hitText)
    }

    private class NoticeVH(v: View) : RecyclerView.ViewHolder(v) {
        val nt: TextView = v.findViewById(R.id.nt)
        val tic: ImageView = v.findViewById(R.id.ntic)
    }

    companion object {
        /** 屏幕上正开着的那条会话；NotifyService 读它决定要不要弹 */
        @Volatile var liveConv: String? = null

        const val REQ_ALBUM = 41
        const val REQ_FILE = 42
        const val COLS = 7   // 表情一页一行几列（和面板 220dp 高配 4 行 52dp）
        /** 常用那一小片，不是全量 Unicode：面板要能一屏滚完，且这些都是文本，后端存的就是字。
         *  按码点列而不是把字面 emoji 写进源码——那串字节在编辑工具里会掉字（我先写过字面量，
         *  结果源码里留下四个空串，面板上就是四个点了没反应的白格）。
         *  每格带一句含义，长按那一格就是读这个；码点和含义写在一起，不会各改各的对不上。 */
        val EMOJI: List<Pair<Int, String>> = listOf(
            0x1F600 to "咧嘴笑", 0x1F601 to "笑得眯眼", 0x1F602 to "笑出泪", 0x1F603 to "张嘴笑",
            0x1F604 to "笑逐颜开", 0x1F605 to "擦汗的笑", 0x1F606 to "笑到闭眼", 0x1F607 to "装乖",
            0x1F608 to "使坏", 0x1F609 to "眨眼", 0x1F60A to "脸红", 0x1F60B to "好吃",
            0x1F60C to "松了口气", 0x1F60D to "喜欢", 0x1F60E to "得意", 0x1F60F to "坏笑",
            0x1F610 to "面无表情", 0x1F611 to "无语", 0x1F612 to "郁闷", 0x1F613 to "冒汗",
            0x1F614 to "失落", 0x1F615 to "为难", 0x1F616 to "难受", 0x1F617 to "想亲",
            0x1F618 to "飞吻", 0x1F619 to "亲亲",
            0x1F641 to "不太高兴", 0x1F642 to "微微一笑", 0x1F643 to "苦笑",
            0x1F44D to "赞", 0x1F44E to "不行", 0x1F44C to "可以", 0x1F64F to "拜托",
            0x1F44F to "鼓掌", 0x1F4AA to "加油", 0x1F91D to "握手",
            0x2764 to "爱心", 0x1F494 to "心碎", 0x1F4AF to "满分", 0x1F389 to "庆祝",
            0x1F31F to "闪亮", 0x1F525 to "火了", 0x2728 to "星星", 0x1F381 to "礼物",
            0x1F4B0 to "有钱")
    }
}
