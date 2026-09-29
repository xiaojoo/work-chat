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
    private val onState: (String) -> Unit = {},
    /** 每次连上都调一次（首连和重连都算），调用方在这里补拉历史和补发状态 */
    private val onReady: () -> Unit = {},
    /**
     * 网关对过期的 access 票直接 401，握手就挡在这里。给一条续期的路：
     * 换到票返回新的 accessToken，换不到返回 null。不传的话保持老行为（挡在门外一直白敲）。
     */
    private val refresh: (() -> String?)? = null
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var beat: java.util.Timer? = null
    /** 续期成功后换成这张新票去连；构造时那张可能已经过期了 */
    @Volatile private var liveToken = token

    // close() 之后不再自动重连：退出登录时这条链路必须真的死掉，
    // 否则通知服务会被自己的重试逻辑救活，继续弹不该弹的消息
    @Volatile private var stopped = false
    private var attempt = 0
    private val retryTask = Runnable { connect() }

    fun open() {
        stopped = false
        attempt = 0
        connect()
    }

    private fun connect() {
        if (stopped) return
        val req = Request.Builder().url("$url?token=$liveToken&deviceId=$deviceId").build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                attempt = 0
                onState("已连接")
                main.post { onReady() }
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
                /* 401 是"这张票过期了"，不是"网断了"。老行为是背着同一张死票无限重连：
                   界面上看不出区别（会话列表走本机缓存），只是每几秒白敲一次网关，永远回不来。 */
                if (response?.code == 401) {
                    val renew = refresh
                    if (renew == null) {
                        onState("登录已过期，请重新登录")
                        return
                    }
                    onState("登录凭据过期，正在续期…")
                    val th = Thread {
                        val fresh = runCatching { renew() }.getOrNull()
                        main.post {
                            if (stopped) return@post
                            if (fresh.isNullOrEmpty()) {
                                onState("登录已过期，请重新登录")
                                stopped = true      // 换不到就停手，别空转敲网关
                            } else {
                                liveToken = fresh
                                attempt = 0
                                connect()
                            }
                        }
                    }
                    th.name = "ws-refresh"
                    th.start()
                    return
                }
                onState("连接断开：${t.message}")
                scheduleRetry()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                onState("连接关闭 $code")
                scheduleRetry()
            }
        })
    }

    /** 1/2/4/8/16 秒之后封顶 30 秒，和桌面端 client.js 同一套节奏：
     *  网关重启时不让一堆手机在同一秒全部打回来。 */
    private fun scheduleRetry() {
        if (stopped) return
        beat?.cancel(); beat = null
        val delay = minOf(30_000L, 1000L shl attempt.coerceAtMost(5))
        attempt++
        main.removeCallbacks(retryTask)
        main.postDelayed(retryTask, delay)
    }

    /**
     * 发一帧，返回这一笔用的 requestId（调用方要拿它对回执）。
     * 原来这个 id 是这里现编的 `类型-毫秒`：同一毫秒发两条同类型的会被网关当成重发吃掉一条，
     * 而且调用方拿不到 id，就没法把 ACK 对到哪一行气泡上。
     */
    fun send(type: String, data: JSONObject, requestId: String? = null): String? {
        val rid = requestId ?: "$type-${System.currentTimeMillis()}-${(0..9999).random()}"
        val frame = JSONObject().put("type", type)
            .put("requestId", rid)
            .put("data", data)
        val s = socket ?: return null
        return if (s.send(frame.toString())) rid else null
    }

    fun close() {
        stopped = true
        main.removeCallbacks(retryTask)
        beat?.cancel(); beat = null
        try { socket?.close(1000, null) } catch (_: Exception) {}
        socket = null
    }
}
