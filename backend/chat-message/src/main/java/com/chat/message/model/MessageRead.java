package com.chat.message.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息已读状态 (纯POJO)
 */
public class MessageRead {

    private String conversationId;
    private Long userId;
    private UUID lastReadMessageId;
    private Instant lastReadTime;

    public MessageRead() {}

    public MessageRead(String conversationId, Long userId, UUID lastReadMessageId) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.lastReadMessageId = lastReadMessageId;
        this.lastReadTime = Instant.now();
    }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public UUID getLastReadMessageId() { return lastReadMessageId; }
    public void setLastReadMessageId(UUID lastReadMessageId) { this.lastReadMessageId = lastReadMessageId; }
    public Instant getLastReadTime() { return lastReadTime; }
    public void setLastReadTime(Instant lastReadTime) { this.lastReadTime = lastReadTime; }
}
