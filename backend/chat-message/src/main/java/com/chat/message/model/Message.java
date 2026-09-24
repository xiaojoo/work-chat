package com.chat.message.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 消息模型 (纯POJO，不依赖Spring Data)
 */
public class Message {

    private String conversationId;
    private LocalDate messageDate;
    private UUID messageId;
    private Long senderId;
    private String messageType;
    private String content;
    private String extra;
    private Instant createTime;

    public Message() {}

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public LocalDate getMessageDate() { return messageDate; }
    public void setMessageDate(LocalDate messageDate) { this.messageDate = messageDate; }
    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }
    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }
}
