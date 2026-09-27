package com.chat.mobile

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

/**
 * 网关长连接。协议照桌面端 client.js 和真机抓到的帧：
 *   发 {"type":"...","requestId":"...","data":{...}}
 *   LOAD_MESSAGES 回来 data 是**数组**（服务端给的是新→旧，界面要倒过来）
 *   帧里的 senderId / userId 是 JSON 数字，不是字符串
 * PING 走应用层文本帧（桌面端就是这么发的），不用协议层 ping，免得网关不认。
 */
class Ws(
    private val url: String,
    private val token: String,
    private val deviceId: String,
    private val onFrame: (JSONObject) -> Unit,
    private val onState: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var beat: java.util.Timer? = null

    fun open() {
        val req = Request.Builder().url("$url?token=$token&deviceId=$deviceId").build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onState("已连接")
                beat = timer(name = "ws-ping", daemon = true, initialDelay = 30_000, period = 30_000) {
                    try { ws.send(JSONObject().put("type", "PING").toString()) } catch (_: Exception) {}
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                if (o.optString("type") == "PONG") return
                main.post { onFrame(o) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onState("连接断开：${t.message}")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                onState("连接关闭 $code")
            }
        })
    }

    fun send(type: String, data: JSONObject) {
        val frame = JSONObject().put("type", type)
            .put("requestId", "$type-${System.currentTimeMillis()}")
            .put("data", data)
        socket?.send(frame.toString())
    }

    fun close() {
        beat?.cancel(); beat = null
        try { socket?.close(1000, null) } catch (_: Exception) {}
        socket = null
    }
}
