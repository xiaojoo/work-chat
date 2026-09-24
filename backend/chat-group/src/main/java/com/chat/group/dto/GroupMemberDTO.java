package com.chat.group.dto;

import java.time.LocalDateTime;

public class GroupMemberDTO {
    private Long id;
    private Long groupId;
    private Long userId;
    private Short role;
    private LocalDateTime muteUntil;
    private LocalDateTime joinTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Short getRole() { return role; }
    public void setRole(Short role) { this.role = role; }
    public LocalDateTime getMuteUntil() { return muteUntil; }
    public void setMuteUntil(LocalDateTime muteUntil) { this.muteUntil = muteUntil; }
    public LocalDateTime getJoinTime() { return joinTime; }
    public void setJoinTime(LocalDateTime joinTime) { this.joinTime = joinTime; }
}
