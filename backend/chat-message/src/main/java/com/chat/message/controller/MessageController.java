package com.chat.message.controller;

import com.chat.message.config.AuthFilter;
import com.chat.message.dto.SendMessageRequest;
import com.chat.message.model.Message;
import com.chat.message.model.MessageRead;
import com.chat.message.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/message")
@CrossOrigin(origins = "*")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/send")
    public ResponseEntity<Message> sendMessage(
            @RequestAttribute(AuthFilter.ATTR_USER_ID) Long userId,
            @RequestBody SendMessageRequest request) {
        // 发送者只认令牌里的身份，请求体里的 senderId 一律覆盖
        request.setSenderId(userId);
        return ResponseEntity.ok(messageService.saveMessage(request));
    }

    @GetMapping("/list/{conversationId}")
    public ResponseEntity<List<Message>> getMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(messageService.getMessages(conversationId, limit));
    }

    /**
     * 分页加载更早的消息
     * @param beforeMessageId 消息游标（不含此ID）
     * @param messageDate 游标所在日期
     */
    @GetMapping("/history/{conversationId}")
    public ResponseEntity<List<Message>> getMessageHistory(
            @PathVariable String conversationId,
            @RequestParam String beforeMessageId,
            @RequestParam String messageDate,
            @RequestParam(defaultValue = "50") int limit) {
        UUID beforeId = UUID.fromString(beforeMessageId);
        LocalDate date = LocalDate.parse(messageDate);
        return ResponseEntity.ok(messageService.getMessagesBefore(conversationId, beforeId, date, limit));
    }

    /**
     * 删除消息
     */
    @DeleteMapping("/{conversationId}/{messageId}")
    public ResponseEntity<Map<String, String>> deleteMessage(
            @RequestAttribute(AuthFilter.ATTR_USER_ID) Long userId,
            @PathVariable String conversationId,
            @PathVariable String messageId) {
        MessageService.Revoke r = messageService.revoke(
                conversationId, java.util.UUID.fromString(messageId), userId);
        return switch (r) {
            case OK -> ResponseEntity.ok(Map.of("status", "ok"));
            case NOT_OWNER -> ResponseEntity.status(403)
                    .body(Map.of("status", "failed", "error", "只能撤回自己发出的消息"));
            case TOO_LATE -> ResponseEntity.badRequest()
                    .body(Map.of("status", "failed", "error", "超过 2 分钟，不能撤回"));
            case NOT_FOUND -> ResponseEntity.status(404)
                    .body(Map.of("status", "failed", "error", "消息不存在"));
        };
    }

    /**
     * 标记消息已读
     */
    @PostMapping("/read")
    public ResponseEntity<Map<String, String>> markAsRead(
            @RequestAttribute(AuthFilter.ATTR_USER_ID) Long userId,
            @RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        String lastReadMessageId = (String) body.get("lastReadMessageId");

        messageService.markAsRead(conversationId, userId, UUID.fromString(lastReadMessageId));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 获取已读状态
     */
    @GetMapping("/read/{conversationId}")
    public ResponseEntity<List<MessageRead>> getReadStatus(@PathVariable String conversationId) {
        return ResponseEntity.ok(messageService.getReadStatus(conversationId));
    }
}
