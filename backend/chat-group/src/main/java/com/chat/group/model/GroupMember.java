package com.chat.group.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_group_member")
public class GroupMember {

    @Id
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 角色: 0=普通成员, 1=管理员, 2=群主
     */
    @Column(nullable = false)
    private Short role = 0;

    @Column(name = "mute_until")
    private LocalDateTime muteUntil;

    @Column(name = "join_time", nullable = false)
    private LocalDateTime joinTime;

    @PrePersist
    protected void onCreate() {
        joinTime = LocalDateTime.now();
    }

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
