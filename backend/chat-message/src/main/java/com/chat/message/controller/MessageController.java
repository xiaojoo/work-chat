package com.chat.message.controller;

import com.chat.message.config.AuthFilter;
import com.chat.message.dto.SendMessageRequest;
import com.chat.message.model.Message;
import com.chat.message.model.MessageRead;
import com.chat.message.search.SearchProvider;
import com.chat.message.search.SummaryProvider;
import com.chat.message.search.SummaryService;
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
    private final com.chat.message.search.SearchService searchService;
    private final SummaryService summaryService;

    public MessageController(MessageService messageService,
                             com.chat.message.search.SearchService searchService,
                             SummaryService summaryService) {
        this.messageService = messageService;
        this.searchService = searchService;
        this.summaryService = summaryService;
    }

    @PostMapping("/send")
    public ResponseEntity<Message> sendMessage(
            @RequestAttribute(AuthFilter.ATTR_USER_ID) Long userId,
            @RequestBody SendMessageRequest request) {
        // 发送者只认令牌里的身份，请求体里的 senderId 一律覆盖
        request.setSenderId(userId);
        return ResponseEntity.ok(messageService.saveMessage(request));
    }

    /**
     * 送达回执：收件人这一端的设备确认"这条真的落到我设备上了"。
     * 谁报的只认令牌里的身份，否则任何人都能替别人伪造"已送达"。
     */
    @PostMapping("/delivered")
    public ResponseEntity<Map<String, String>> markDelivered(
            @RequestAttribute(AuthFilter.ATTR_USER_ID) Long userId,
            @RequestBody Map<String, Object> body) {
        Object conv = body.get("conversationId");
        Object ids = body.get("messageIds");
        if (conv == null || !(ids instanceof java.util.List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "conversationId 和 messageIds 必填"));
        }
        @SuppressWarnings("unchecked")
        java.util.List<String> messageIds = (java.util.List<String>) ids;
        messageService.markDelivered(String.valueOf(conv), messageIds, userId);
        return ResponseEntity.ok(Map.of("marked", String.valueOf(messageIds.size())));
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
     * 会话内关键词检索。一期只有"本机扫日分区"这一个后端，
     * 换 WeKnora 是再写一个 SearchProvider 实现 + 改 search.provider 配置，这一层不动。
     * <p>
     * 成员资格不在这里判（和 /list、/history 一样是"有效令牌就能读"），判在网关那条 SEARCH_MESSAGES 帧上；
     * 这条既有缺口要一起收，别在检索上单独补一个假的样子。
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String conversationId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "30") int days) {
        SearchProvider.Result r = searchService.search(
                new SearchProvider.Request(conversationId, keyword, limit, days));
        return ResponseEntity.ok(Map.of(
                "provider", r.provider(),
                "hits", r.hits(),
                "scanned", r.scanned(),
                "truncated", r.truncated(),
                "tookMs", r.tookMs()));
    }

    /**
     * 补发：游标（含所在分区日期）之后、直到今天的消息，正序。断线重连时用。
     * 会话成员资格不在这里判 —— 这一层拿不到成员表，判在网关（它有人脉：用户服务/群服务）。
     */
    @GetMapping("/after/{conversationId}")
    public ResponseEntity<List<Message>> getMessagesAfter(
            @PathVariable String conversationId,
            @RequestParam(required = false) String afterMessageId,
            @RequestParam String messageDate,
            @RequestParam(defaultValue = "100") int limit) {
        UUID afterId = null;
        LocalDate date;
        try {
            // 空游标 = 这条会话我一条都没看过，从补发窗口头开始整体给
            if (afterMessageId != null && !afterMessageId.isBlank()) {
                afterId = UUID.fromString(afterMessageId);
            }
            date = LocalDate.parse(messageDate);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        // 上限卡死：补发一次最多 200 条，客户端拿到正好 200 条会自己接着问下一段
        return ResponseEntity.ok(messageService.getMessagesAfter(
                conversationId, afterId, date, Math.max(1, Math.min(limit, 200))));
    }

    /**
     * 归纳一批检索命中。客户端只给 (id, date) 这对键，原文由服务端从库里读 ——
     * 提示词的内容不能由调用方决定，否则这颗按钮就成了往模型里塞话的入口。
     */
    @PostMapping("/search/summarize")
    public ResponseEntity<Map<String, Object>> summarize(
            @RequestParam String conversationId,
            @RequestBody Map<String, Object> body) {
        String keyword = String.valueOf(body.getOrDefault("keyword", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = body.get("items") instanceof List
                ? (List<Map<String, String>>) body.get("items") : List.of();
        SummaryProvider.Outcome o = summaryService.summarize(conversationId, keyword, items);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("provider", o.model() == null || o.model().isEmpty() ? "none" : o.model());
        out.put("tookMs", o.tookMs());
        out.put("summary", o.text());
        out.put("error", o.error());
        return ResponseEntity.ok(out);
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
