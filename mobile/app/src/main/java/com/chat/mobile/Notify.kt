package com.chat.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import org.json.JSONObject

/**
 * 消息通知：一条会话挂一条（点开直达那条会话），上面再挂一条汇总，桌面角标数 = 未读合计。
 *
 * 两条渠道：
 *  - msg：高优先级。下拉里有横幅、锁屏给看内容（`VISIBILITY_PUBLIC`——你要的就是不解锁也能读，
 *    代价是手机躺在桌上时别人能看到消息正文）。
 *  - svc：常驻通道那条。最低优先级，不响不弹横幅，只说明那条连接活着。
 *
 * 角标：Android 原生没有"往桌面图标写数字"的公开 API。这里两路都试——
 *  `setNumber(未读合计)` 走通知自带的那个数，MIUI 再单独敲它桌面那个 badge provider；
 *  哪一路在这台 MIUI 14 上真亮出数字，以量到的为准（见回报）。
 */
object Notify {

    /** 渠道的优先级/震动**建好之后改不动**（系统按 id 记着用户设定），
     *  要加震动只能换 id 重建；旧那条删掉，不然设置里会同时躺着两颗"新消息"。 */
    const val CHAN_MSG = "chat-msg-v2"
    /** v3 是"会话渠道"那次实验留下的：标记没生效，但渠道建出来了，不删会在设置里多一颗"新消息" */
    private val CHAN_MSG_OLD = listOf("chat-msg", "chat-msg-v3")
    const val CHAN_SVC = "chat-svc"
    const val GROUP = "chat.messages"
    const val SVC_ID = 1
    private const val SUMMARY_ID = 0x7A01

    /** 两下短振：60ms 振 / 80ms 停 / 60ms 振，不循环 */
    private val VIBE = longArrayOf(0, 60, 80, 60)

    fun ensureChannels(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java)
        CHAN_MSG_OLD.forEach { m.deleteNotificationChannel(it) }
        m.createNotificationChannel(NotificationChannel(CHAN_MSG, "新消息",
            NotificationManager.IMPORTANCE_HIGH).apply {
                description = "来新消息时弹横幅、响铃、震动，锁屏显示内容"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                /** 波形只能用旧的那个 setter：这台 MIUI 14 的 framework.jar 里没有
                 *  `NotificationChannel.setVibrationEffect(VibrationEffect)`，
                 *  用 `vibrationEffect = ...` 会在 MainActivity.onCreate 里直接 NoSuchMethodError 崩掉整个 app
                 *  （21:57/21:58 两次崩溃日志为证）。包一层是防着 MIUI 连旧的也改掉——那样至少通知还在。 */
                @Suppress("DEPRECATION")
                runCatching { vibrationPattern = VIBE }
            })
        m.createNotificationChannel(NotificationChannel(CHAN_SVC, "消息通道",
            NotificationManager.IMPORTANCE_MIN).apply {
                description = "后台那条长连接，不响不弹横幅、不震动"
                setShowBadge(false)
            })
    }

    private fun nm(ctx: Context) = NotificationManagerCompat.from(ctx)

    /** 点通知 = 进那条会话。requestCode 用会话 id 的哈希，免得两条通知共用一个 PendingIntent 互相顶掉 */
    private fun openIntent(ctx: Context, convId: String, name: String): PendingIntent {
        val i = Intent(ctx, ChatActivity::class.java)
            .putExtra("conv", convId).putExtra("name", name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, convId.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun me(ctx: Context) =
        Person.Builder().setName(Cfg.username(ctx).ifEmpty { "我" }).build()

    /** 一条会话的那颗。text 已经是渲染成人话的内容（图片/文件走各自的占位说法）。
     *  group=false（单聊）时不声明成群聊：否则平台会在标题前再叠一层发送人名，
     *  单聊就变成「probeB：probeB」。 */
    fun post(ctx: Context, convId: String, convName: String, sender: String,
             text: String, at: Long, total: Int, group: Boolean) {
        val s = NotificationCompat.MessagingStyle(me(ctx))
        if (group) s.setGroupConversation(true).setConversationTitle(convName)
        s.addMessage(text, at, Person.Builder().setName(sender).build())
        val n = NotificationCompat.Builder(ctx, CHAN_MSG)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setStyle(s)
            .setContentTitle(convName).setContentText(text)
            .setContentIntent(openIntent(ctx, convId, convName))
            .setDeleteIntent(cancelIntent(ctx, convId))
            .setAutoCancel(true)
            .setNumber(total)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        runCatching { nm(ctx).notify(convId.hashCode(), n) }
            .onFailure { android.util.Log.w("notify", "这条通知没发出去", it) }
    }

    /** 划掉某条通知 = 只撤这一条的显示，未读还在服务端，下次来消息照样顶回来 */
    private fun cancelIntent(ctx: Context, convId: String): PendingIntent {
        val i = Intent(ctx, NotifyService::class.java).setAction(NotifyService.ACT_DISMISS)
            .putExtra("conv", convId)
        return PendingIntent.getService(ctx, convId.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 汇总那颗：列表式列出每条会话的最新一句，点开进消息页。
     *  两处"组规则"必须和会话那颗对齐，否则通知只会静静躺在下拉里——不响、不弹横幅、锁屏也不给看：
     *  - 平台在锁屏上**只画组里那颗汇总**，子颗被收掉，所以汇总自己也得 vis=PUBLIC；
     *  - 出不出声由**汇总那颗的 groupAlertBehavior** 决定，写 SUMMARY 等于把子颗全部消音。 */
    fun summary(ctx: Context, rows: List<Pair<String, String>>, total: Int) {
        if (rows.isEmpty()) { nm(ctx).cancel(SUMMARY_ID); return }
        val inbox = NotificationCompat.InboxStyle()
        rows.take(5).forEach { (nm_, txt) -> inbox.addLine("$nm_：$txt") }
        inbox.setSummaryText(if (total > 0) "未读 $total 条" else "${rows.size} 条新消息")
        val n = NotificationCompat.Builder(ctx, CHAN_MSG)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(if (total > 0) "$total 条未读" else "新消息")
            .setContentText(rows.first().let { "${it.first}：${it.second}" })
            .setStyle(inbox)
            .setContentIntent(PendingIntent.getActivity(ctx, SUMMARY_ID,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setNumber(total)
            .build()
        runCatching { nm(ctx).notify(SUMMARY_ID, n) }
    }

    /** 会话在屏幕上被打开 / 服务端未读清了，就把那颗撤掉 */
    fun cancel(ctx: Context, convId: String) { nm(ctx).cancel(convId.hashCode()) }

    fun cancelAll(ctx: Context) { nm(ctx).cancelAll() }

    /** 把网关帧里的一条消息变成人话：图片/文件在 content 里是对象，不是纯文本 */
    fun render(m: JSONObject): String {
        val raw = if (m.isNull("content")) "" else m.optString("content")
        return when (m.optString("messageType")) {
            // content 不是 JSON 时别把整条通知弄崩，退回原文
            "IMAGE", "FILE" -> runCatching {
                val o = JSONObject(raw)
                (if (m.optString("messageType") == "IMAGE") "[图片] " else "[文件] ") +
                    o.optString("name")
            }.getOrDefault(if (m.optString("messageType") == "IMAGE") "[图片]" else "[文件]")
            else -> raw
        }
    }
}
