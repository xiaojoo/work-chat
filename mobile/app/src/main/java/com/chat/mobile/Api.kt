package com.chat.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后端返回的是裸数组/裸对象（没有 {code,data} 那层包装），这点是照真实响应写的：
 * GET /api/conversation/list -> [...]，POST /api/auth/login -> {accessToken,...}
 * 失败时是 {error: "..."} 且 http 码不一定 4xx，所以判断要看 body 里有没有 error。
 */
class Api(private val base: String, private val token: String = "") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    class Failure(message: String) : Exception(message)

    private fun call(path: String, body: String? = null, method: String = if (body == null) "GET" else "POST"): String {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        if (token.isNotEmpty()) builder.addHeader("Authorization", "Bearer $token")
        builder.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Failure("${resp.code} ${text.take(120)}")
            return text
        }
    }

    /** 登录成功返回 accessToken/userId/username；失败抛 Failure，消息就是后端那句 error。
     *  userId 后端返回的是 JSON 数字而不是字符串，getString 在 JVM 的 org.json 上会直接抛
     *  "is not a string"，所以一律 get().toString() 收成 Session —— 不让行为跟着平台变 */
    data class Session(val token: String, val userId: String, val username: String)

    fun login(username: String, password: String): Session {
        val o = JSONObject(call("/api/auth/login", JSONObject().put("username", username).put("password", password).toString()))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        val t = o.optString("accessToken")
        if (t.isEmpty()) throw Failure("响应里没有 accessToken：$o")
        return Session(
            token = t,
            userId = o.opt("userId")?.toString() ?: "",
            username = o.opt("username")?.toString() ?: username
        )
    }

    data class Conversation(
        val id: String, val type: Int, val name: String, val targetId: String,
        val lastMessage: String, val lastMessageTime: String, val unread: Int
    )

    fun conversations(userId: String): List<Conversation> {
        val arr = JSONArray(call("/api/conversation/list?userId=$userId"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Conversation(
                id = o.getString("id"),
                type = o.optInt("type"),
                name = o.optString("name"),
                targetId = o.optString("targetId"),
                lastMessage = o.optString("lastMessage"),
                lastMessageTime = o.optString("lastMessageTime"),
                unread = o.optInt("unreadCount")
            )
        }
    }
}
