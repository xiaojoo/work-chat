package com.chat.user.controller;

import com.chat.user.dto.ConversationDTO;
import com.chat.user.model.Conversation;
import com.chat.user.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversation")
@CrossOrigin(origins = "*")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    private Long getUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }

    /**
     * 创建一对一会话
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Long>> createConversation(
            Authentication auth,
            @RequestBody Map<String, Long> body) {
        Long targetUserId = body.get("targetUserId");
        Long userId = getUserId(auth);
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }
        Conversation conv = conversationService.getOrCreatePrivateConversation(userId, targetUserId);
        Map<String, Long> result = new HashMap<>();
        result.put("conversationId", conv.getId());
        return ResponseEntity.ok(result);
    }

    /**
     * 创建群聊会话（内部接口，供群组服务调用）
     */
    @PostMapping("/create-group")
    public ResponseEntity<Map<String, Long>> createGroupConversation(
            @RequestBody Map<String, Long> body) {
        Long groupId = body.get("groupId");
        Conversation conv = conversationService.createGroupConversation(groupId);
        Map<String, Long> result = new HashMap<>();
        result.put("conversationId", conv.getId());
        return ResponseEntity.ok(result);
    }

    /**
     * 绑定用户与群聊会话（内部接口）
     */
    @PostMapping("/bind-group")
    public ResponseEntity<Map<String, String>> bindGroupConversation(
            @RequestBody Map<String, Long> body) {
        Long userId = body.get("userId");
        Long groupId = body.get("groupId");
        // 先找到群聊会话
        Conversation conv = conversationService.createGroupConversation(groupId);
        conversationService.bindUserConversation(userId, conv.getId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 增加未读消息数（内部接口）
     */
    @PostMapping("/unread/inc")
    public ResponseEntity<Map<String, String>> incrementUnread(
            @RequestBody Map<String, Long> body) {
        conversationService.incrementUnreadCount(body.get("conversationId"), body.get("userId"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 清除未读消息数
     */
    @PostMapping("/unread/clear")
    public ResponseEntity<Map<String, String>> clearUnread(
            Authentication auth,
            @RequestBody Map<String, Long> body) {
        conversationService.clearUnreadCount(body.get("conversationId"), getUserId(auth));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, String>> deleteConversation(
            Authentication auth,
            @PathVariable Long conversationId) {
        conversationService.deleteConversationForUser(getUserId(auth), conversationId);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /**
     * 获取会话中的用户ID列表（内部接口，供 Gateway 调用）
     */
    @GetMapping("/users/{conversationId}")
    public ResponseEntity<Map<String, List<Long>>> getConversationUsers(
            @PathVariable Long conversationId) {
        List<Long> userIds = conversationService.getConversationUserIds(conversationId);
        return ResponseEntity.ok(Map.of("userIds", userIds));
    }

    /**
     * 更新会话最后消息（内部接口，供 Gateway 调用）
     */
    @PostMapping("/update-last-message")
    public ResponseEntity<Map<String, String>> updateLastMessage(
            @RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        Long userId = Long.valueOf(body.get("userId").toString());
        String content = (String) body.get("content");
        conversationService.updateLastMessage(Long.parseLong(conversationId), userId, content, content);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<ConversationDTO>> getConversationList(Authentication auth) {
        return ResponseEntity.ok(conversationService.getConversationList(getUserId(auth)));
    }
}
