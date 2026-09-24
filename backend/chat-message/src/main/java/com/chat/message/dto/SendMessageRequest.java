package com.chat.message.dto;

public class SendMessageRequest {
    private String conversationId;
    private Long senderId;
    private String messageType;
    private String content;
    private String extra;

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }
}
