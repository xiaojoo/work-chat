package com.chat.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 送达回执：这条消息落到本机了就得报上去——网关写进 message_delivered，再转给同会话里的其他人，
 * 对方气泡上那句"已送达"就是这份回执。
 *
 * 三个收帧的地方都要走这一句（聊天页、会话列表页、后台通知服务）。以前只有聊天页那条有，
 * 结果人停在列表页或屏幕亮着没进会话时，对方永远等不到回执 —— 送达说的是"设备收到了"，
 * 和哪个界面正开着没关系。
 */
object Delivery {
    fun echo(ws: Ws?, m: JSONObject, myId: String) {
        if (m.optString("type") != "MESSAGE_RECEIVE") return
        val d = m.optJSONObject("data") ?: return
        val mid = d.optString("messageId")
        if (mid.isEmpty()) return
        // 自己另一个入口发出去的不回（和服务端跳过 senderId 算未读是同一条规则）
        if (myId.isNotEmpty() && d.opt("senderId")?.toString() == myId) return
        ws?.send("MESSAGE_DELIVERED", JSONObject()
            .put("conversationId", d.optString("conversationId"))
            .put("messageIds", JSONArray().put(mid)))
    }
}
