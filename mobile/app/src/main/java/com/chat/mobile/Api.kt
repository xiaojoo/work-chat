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

    private fun call(path: String, body: String? = null, method: String? = null): String {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        if (token.isNotEmpty()) builder.addHeader("Authorization", "Bearer $token")
        val verb = method ?: if (body == null) "GET" else "POST"
        builder.method(verb, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
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

    /** 删会话：DELETE /api/conversation/{纯数字id}?userId= 。群会话同样要去掉 g 前缀。
     *  这是这一排菜单里唯一真打到服务器的一条，其余（置顶/免打扰/不显示）后端没有字段。 */
    fun deleteConversation(conversationId: String, userId: String) {
        val numeric = conversationId.removePrefix("g")
        call("/api/conversation/$numeric?userId=$userId", method = "DELETE")
    }

    /** POST /api/conversation/create，body 是 Map<String,Long> 所以 targetUserId 要发数字。
     *  发起人从令牌里取（控制器签名是 Authentication），不靠查询参数。返回纯数字会话 id。
     *  桌面端 api/conversation.js 的 startChatWithFriend 走的也是这一条：先查已有会话，没有才建。 */
    fun createConversation(targetUserId: String): String {
        val o = JSONObject(call("/api/conversation/create",
            JSONObject().put("targetUserId", targetUserId.toLong()).toString()))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        return o.opt("conversationId")?.toString() ?: ""
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

    /** 后端只认这四个字面键，多传的会被无视；body 是 Map<String,String>，所以值不能为 null */
    private fun toProfile(o: JSONObject, fallbackId: String) = Profile(
        id = o.opt("id")?.toString() ?: fallbackId,
        username = str(o, "username"),
        nickname = str(o, "nickname"),
        avatar = str(o, "avatar"),
        department = str(o, "department"),
        position = str(o, "position"),
        email = str(o, "email"),
        phone = str(o, "phone"),
        bio = str(o, "bio"))

    /** GET /api/user/{id}。remark、location 后端恒返回空串且没有任何写入路径，不收进这个类 */
    fun profile(userId: String): Profile {
        val o = JSONObject(call("/api/user/$userId"))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        return toProfile(o, userId)
    }

    /** PUT /api/user/profile：只发改动了的那一个键（UserController 按 containsKey 逐条应用），
     *  返回体就是新资料，直接拿它刷界面，不用再多打一次 GET。 */
    fun updateProfile(key: String, value: String, userId: String): Profile {
        val o = JSONObject(call("/api/user/profile", JSONObject().put(key, value).toString(), "PUT"))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        return toProfile(o, userId)
    }

    // ===== 群：成员和邀请在 chat-group(8084)，组织树在 chat-user(8081)，两个 base 都要用 =====

    data class Member(val userId: String, val role: Int)

    /** GET /api/group/{纯数字 groupId}/members。role=2 是群主，0 是普通成员 */
    fun groupMembers(groupId: String): List<Member> {
        val arr = JSONArray(call("/api/group/${groupId.removePrefix("g")}/members"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Member(o.opt("userId")?.toString() ?: "", o.optInt("role"))
        }
    }

    data class OrgPerson(val id: String, val nickname: String, val username: String, val position: String)
    data class OrgDept(val name: String, val people: List<OrgPerson>)

    /** GET /api/user/org：[{department, count, members:[...]}]，桌面端「添加用户」那棵树同一份数据 */
    fun org(): List<OrgDept> {
        val arr = JSONArray(call("/api/user/org"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ms = o.optJSONArray("members") ?: JSONArray()
            OrgDept(str(o, "department"), List(ms.length()) { k ->
                val p = ms.getJSONObject(k)
                OrgPerson(p.opt("id")?.toString() ?: "", str(p, "nickname"), str(p, "username"), str(p, "position"))
            })
        }
    }

    /** POST /api/group/{id}/invite {userIds:[...]}。后端签名是 List<Long>，所以发数字不是字符串 */
    fun invite(groupId: String, userIds: List<String>) {
        val ids = JSONArray().apply { userIds.forEach { put(it.toLong()) } }
        call("/api/group/${groupId.removePrefix("g")}/invite", JSONObject().put("userIds", ids).toString())
    }

    /** 单聊转群聊走这一条：POST /api/group/create {name, memberIds:[...]}，返回新群的纯数字 id。
     *  后端 createGroupWithMembers = 建群（我落成群主）+ 邀请这批人，并给每人绑好那条群会话，
     *  所以建完直接就能用 "g"+id 打开，不用再查一遍会话列表。name 是 @NotBlank、上限 100。 */
    fun createGroup(name: String, memberIds: List<String>): String {
        val ids = JSONArray().apply { memberIds.forEach { put(it.toLong()) } }
        val o = JSONObject(call("/api/group/create",
            JSONObject().put("name", name).put("memberIds", ids).toString()))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        return o.opt("id")?.toString() ?: ""
    }
}
