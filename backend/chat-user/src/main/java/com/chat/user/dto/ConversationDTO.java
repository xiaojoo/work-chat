package com.chat.user.dto;

import java.time.LocalDateTime;

public class ConversationDTO {
    private String id;
    private Short type;
    private Long targetId;
    private String name;
    private String avatar;
    private String lastMessage;
    private LocalDateTime lastMessageTime;
    private Integer unreadCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public void setId(Long id) { this.id = String.valueOf(id); }
    public Short getType() { return type; }
    public void setType(Short type) { this.type = type; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public LocalDateTime getLastMessageTime() { return lastMessageTime; }
    public void setLastMessageTime(LocalDateTime lastMessageTime) { this.lastMessageTime = lastMessageTime; }
    public Integer getUnreadCount() { return unreadCount; }
    public void setUnreadCount(Integer unreadCount) { this.unreadCount = unreadCount; }
}
