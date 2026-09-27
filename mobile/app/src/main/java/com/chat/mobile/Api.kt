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

    /** 后端对没有值的字段返回的是 JSON null（不是缺键），而 Android 自带的 org.json
     *  对 null 调 optString 会给出字符串 "null"，界面上就真渲染出 null 这三个字母。 */
    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key)

    fun conversations(userId: String): List<Conversation> {
        val arr = JSONArray(call("/api/conversation/list?userId=$userId"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Conversation(
                id = o.getString("id"),
                type = o.optInt("type"),
                name = str(o, "name"),
                targetId = str(o, "targetId"),
                lastMessage = str(o, "lastMessage"),
                lastMessageTime = str(o, "lastMessageTime"),
                unread = o.optInt("unreadCount")
            )
        }
    }

    /** 群会话 id 带 g 前缀，后端要的是纯数字，和桌面端 api/conversation.js 同一条规则。
     *  路径必须带 /api —— 这个类里所有调用都直连服务根地址，没有 axios 那层 baseURL。 */
    fun clearUnread(conversationId: String, userId: String) {
        val numeric = conversationId.removePrefix("g")
        call("/api/conversation/unread/clear?userId=$userId",
            JSONObject().put("conversationId", numeric).put("userId", userId).toString())
    }

    data class Friend(val id: String, val username: String, val nickname: String, val avatar: String)

    /** GET /api/friend/list 只认令牌，不接 userId 查询参数（桌面端 api/friend.js 就没传） */
    fun friends(): List<Friend> {
        val arr = JSONArray(call("/api/friend/list"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            // friendId 是 JSON 数字，一律 get().toString()
            Friend(
                id = o.opt("friendId")?.toString() ?: "",
                username = str(o, "username"),
                nickname = str(o, "nickname"),
                avatar = str(o, "avatar"))
        }
    }

    data class Profile(
        val id: String, val username: String, val nickname: String, val avatar: String,
        val department: String, val position: String, val email: String, val phone: String, val bio: String)

    /** GET /api/user/{id}。remark、location 后端恒返回空串且没有任何写入路径，不收进这个类 */
    fun profile(userId: String): Profile {
        val o = JSONObject(call("/api/user/$userId"))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        return Profile(
            id = o.opt("id")?.toString() ?: userId,
            username = str(o, "username"),
            nickname = str(o, "nickname"),
            avatar = str(o, "avatar"),
            department = str(o, "department"),
            position = str(o, "position"),
            email = str(o, "email"),
            phone = str(o, "phone"),
            bio = str(o, "bio"))
    }
}
