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
    data class Session(val token: String, val userId: String, val username: String, val refreshToken: String = "")

    fun login(username: String, password: String): Session {
        val o = JSONObject(call("/api/auth/login", JSONObject().put("username", username).put("password", password).toString()))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        val t = o.optString("accessToken")
        if (t.isEmpty()) throw Failure("响应里没有 accessToken：$o")
        return Session(
            token = t,
            userId = o.opt("userId")?.toString() ?: "",
            username = o.opt("username")?.toString() ?: username,
            refreshToken = o.optString("refreshToken")
        )
    }

    /**
     * access 票到期了，用 refresh 票换一张。这个调用本身不能带那张过期的 access 票 ——
     * 带了就会被鉴权层挡在门口，续期永远失败，所以这里用不带 token 的 Api(base)。
     */
    fun refresh(refreshToken: String): Session {
        val o = JSONObject(call("/api/auth/refresh", JSONObject().put("refreshToken", refreshToken).toString()))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        val t = o.optString("accessToken")
        if (t.isEmpty()) throw Failure("续期响应里没有 accessToken：$o")
        return Session(
            token = t,
            userId = o.opt("userId")?.toString() ?: "",
            username = o.opt("username")?.toString() ?: "",
            refreshToken = o.optString("refreshToken")
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

    /** 和这个人的单聊会话 id：列表里有就复用，没有才建一条。
     *  通讯录那一行、好友信息页的「发消息」都走这一句，别在两处各写一遍判断。
     *  注意：对**已软删**的绑定，create 会返回原来那个 id 但不撤销 deleted，列表里还是看不见——
     *  真要还原得服务端改标记，这里不负责。 */
    fun privateConvWith(targetUserId: String, myId: String): String =
        conversations(myId).firstOrNull { it.type == 1 && it.targetId == targetUserId }?.id
            ?: createConversation(targetUserId)

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

    /** 加好友：POST /api/friend/add。后端的 friendId 是 Long，所以能当数字发就当数字发，
     *  不依赖 Jackson 把字符串掰成 Long。重复添加后端抛「已经是好友」，
     *  所以选人页先把已有好友整批滤掉再摆出来。 */
    fun addFriend(friendId: String) {
        val body = JSONObject()
        if (friendId.all { it.isDigit() }) body.put("friendId", friendId.toLong())
        else body.put("friendId", friendId)
        call("/api/friend/add", body.toString())
    }

    /** 删好友：DELETE /api/friend/remove/{id}。后端两边各删一条（和"直接互加"对称），
     *  会话和它的聊天记录不动 */
    fun removeFriend(friendId: String) {
        call("/api/friend/remove/$friendId", null, "DELETE")
    }

    /** GET /api/friend/check/{id} → {"isFriend":true}：决定好友信息页那行「删除好友」出不出 */
    fun isFriend(userId: String): Boolean =
        JSONObject(call("/api/friend/check/$userId")).optBoolean("isFriend")

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

    /** POST /api/group/create {name, memberIds:[...]}，返回新群的纯数字 id。
     *  后端 createGroupWithMembers = 建群（我落成群主）+ 邀请这批人，并给每人绑好群会话。 */
    fun createGroup(name: String, memberIds: List<String>): String {
        val ids = JSONArray().apply { memberIds.forEach { put(it.toLong()) } }
        val o = JSONObject(call("/api/group/create",
            JSONObject().put("name", name).put("memberIds", ids).toString()))
        val err = o.optString("error")
        if (err.isNotEmpty()) throw Failure(err)
        return o.opt("id")?.toString() ?: ""
    }

    /** 一条文件/图片消息的内容就是这一串 JSON 引用（桌面端 sendMediaFile 同形状）。
     *  类型按 contentType 分：image/ 开头算 IMAGE，其余 FILE——和 web 侧 previewOf 一个口径。 */
    data class FileRef(val fileId: String, val name: String, val size: Long, val contentType: String) {
        fun content(): String = JSONObject().put("fileId", fileId).put("name", name)
            .put("size", size).put("contentType", contentType).toString()
        fun type(): String = if (contentType.startsWith("image/")) "IMAGE" else "FILE"
        /** 列表/气泡上那句短预览：[图片] xxx.png / [文件] 报告.pdf */
        fun preview(): String = (if (type() == "IMAGE") "[图片] " else "[文件] ") +
            name.ifEmpty { "文件" }
    }

    /** 反过来：把一条消息内容解成文件引用；普通文本和老的 base64 附件都不是这个形状，返回 null。
     *  放在 companion 里是因为渲染气泡时手里没有 Api 实例，这一步也不碰网络。 */
    fun uploadFile(bytes: ByteArray, name: String, mime: String): FileRef {
        val long = http.newBuilder()
            .writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file", name, bytes.toRequestBody(mime.toMediaType()))
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addPart(part).build()
        val req = Request.Builder().url(base.trimEnd('/') + "/api/message/file/upload")
            .addHeader("Authorization", "Bearer $token").post(body).build()
        long.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Failure("${resp.code} ${text.take(120)}")
            val o = JSONObject(text)
            val err = o.optString("error")
            if (err.isNotEmpty()) throw Failure(err)
            return FileRef(o.optString("fileId"), o.optString("name"),
                o.optLong("size"), o.optString("contentType"))
        }
    }

    companion object {
        /** 把一条消息内容解成文件引用；普通文本和老的 base64 附件都不是这个形状，返回 null。
         *  放 companion 是因为渲染气泡时手里没有 Api 实例，这一步也不碰网络。 */
        fun fileRef(content: String): FileRef? {
            val s = content.trim()
            if (!s.startsWith("{") || !s.contains("\"fileId\"")) return null
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return null
            val id = o.optString("fileId")
            if (id.isEmpty()) return null
            return FileRef(id, o.optString("name"), o.optLong("size"), o.optString("contentType"))
        }
    }
}
