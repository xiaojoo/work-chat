package com.chat.mobile

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.concurrent.thread

/**
 * 后台那条长连接 + 通知的唯一出口。
 *
 * 为什么要常驻：这套后端没有系统推送（没接 FCM），消息只能从网关那条 WebSocket 上拿。
 * 进程被杀掉就再也收不到——所以连接挂在这个前台服务上，锁屏、退到桌面都继续收。
 * 代价是下拉里会常驻一条不响不弹的「消息通道」通知（Android 8 起前台服务必须挂一条）。
 *
 * 三条抑制规则，都是"你在看就别吵"：
 *  1) 这条会话正开在屏幕上（ChatActivity.liveConv）；
 *  2) 这条会话被设了免打扰（本机 flag，和会话行那颗铃铛同源）；
 *  3) 帧的发送方就是我自己。
 * 免打扰只压横幅，不改服务端未读，所以角标照旧会涨。
 */
class NotifyService : Service() {

    private var ws: Ws? = null
    private val main = Handler(Looper.getMainLooper())
    /** 会话 id -> 显示名。列表接口拉的，缓存一份，通知不能等网络 */
    private val names = HashMap<String, String>()
    /** 用户 id -> 昵称，来自好友表；单聊的发送方名字从这里读 */
    private val senders = HashMap<String, String>()
    /** 会话 id -> type（1 单聊 / 2 群聊）：通知要不要顶成"群名 + 发送人"两行，看这个 */
    private val types = HashMap<String, Int>()
    private var total = 0
    private var state = "连接中"

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        ServiceCompat.startForeground(this, Notify.SVC_ID, svcNotif(),
            /* 这个类型是 API 34 才有的数；在 33 的机器上传进去平台会判成非法类型，所以只在 34+ 带 */
            if (android.os.Build.VERSION.SDK_INT >= 34)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        refreshDirs()
        open()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACT_DISMISS -> {
                i.getStringExtra("conv")?.let { Notify.cancel(this, it) }
                return START_STICKY
            }
            /* 从会话页退回来：未读已经在服务端清了，只重拉一次合计，别把连接断开重来 */
            ACT_REFRESH -> { refreshDirs(); return START_STICKY }
        }
        if (Cfg.token(this).isEmpty()) stopSelf() else if (ws == null) open()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { ws?.close() }
        ws = null
        super.onDestroy()
    }

    /** 不绑：这个服务只负责把连接养住，界面通过 start 的 action 递话 */
    override fun onBind(i: Intent?): IBinder? = null

    private fun svcNotif(): android.app.Notification {
        val open = PendingIntent.getActivity(this, Notify.SVC_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notify.CHAN_SVC)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle("消息通道")
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun repaint() {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notify.SVC_ID, svcNotif())
        }
    }

    /** 断了 5 秒后再试；MIUI 把进程回收掉的话，START_STICKY 会把这个服务重新拉起来 */
    private fun open() {
        if (Cfg.token(this).isEmpty()) return
        ws?.close()
        ws = Ws(Cfg.wsBase(this), Cfg.token(this), "chat-mobile-notify",
            onFrame = { onFrame(it) },
            onState = { s -> main.post {
                state = s; repaint()
                if (s != "已连接") main.postDelayed({ open() }, 5_000)
            } }).also { it.open() }
    }

    /** 会话名、发信人昵称、未读合计都来自接口：后台线程拉，回到主线程再用。通知不能等网络。 */
    private fun refreshDirs() {
        val uid = Cfg.userId(this)
        if (uid.isEmpty() || Cfg.token(this).isEmpty()) return
        thread(name = "notify-dirs") {
            val api = Api(Cfg.apiBase(this), Cfg.token(this))
            val rows = runCatching { api.conversations(uid) }.getOrNull()
            val fr = runCatching { api.friends() }.getOrNull() ?: emptyList()
            main.post {
                /* 拉失败就整段跳过：一次网络抖动不该把汇总那颗抹掉 */
                if (rows == null) return@post
                rows.forEach { if (it.name.isNotEmpty()) names[it.id] = it.name; types[it.id] = it.type }
                fr.forEach { if (it.nickname.isNotEmpty()) senders[it.id] = it.nickname }
                total = rows.sumOf { it.unread }
                val live = rows.filter { it.unread > 0 && it.id != ChatActivity.liveConv }
                    .map { (names[it.id] ?: "新消息") to it.lastMessage }
                Notify.summary(this, live, total)
            }
        }
    }

    private fun onFrame(m: JSONObject) {
        if (m.optString("type") != "MESSAGE_RECEIVE") return
        val d = m.optJSONObject("data") ?: return
        val convId = d.optString("conversationId")
        if (convId.isEmpty()) return
        if (d.optString("senderId") == Cfg.userId(this)) return
        if (convId == ChatActivity.liveConv) return
        val text = Notify.render(d)
        val sender = senders[d.optString("senderId")] ?: ""
        val at = parseTime(d.optString("createTime"))
        val title = names[convId] ?: sender.ifEmpty { "新消息" }
        /* 免打扰只压横幅和响铃：服务端那条未读它不知道你在看，角标照旧涨 */
        if (!ConvFlags(this).muted(convId)) Notify.post(this, convId, title,
            sender.ifEmpty { title }, text, at, total + 1, types[convId] == 2)
        refreshDirs()
    }

    /** 后端的 createTime 是带时区的 ISO 串，量不到就退回现在这个时刻，不让通知排在最前面 */
    private fun parseTime(s: String): Long = runCatching {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())

    companion object {
        const val ACT_DISMISS = "dismiss"
        const val ACT_REFRESH = "refresh"

        fun start(ctx: Context) {
            if (Cfg.token(ctx).isEmpty()) return
            Notify.ensureChannels(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyService::class.java))
        }

        /** 让服务重拉一次未读合计（角标和汇总那颗跟着新） */
        fun refresh(ctx: Context) {
            if (Cfg.token(ctx).isEmpty()) return
            ContextCompat.startForegroundService(ctx,
                Intent(ctx, NotifyService::class.java).setAction(ACT_REFRESH))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NotifyService::class.java))
            Notify.cancelAll(ctx)
        }
    }
}
