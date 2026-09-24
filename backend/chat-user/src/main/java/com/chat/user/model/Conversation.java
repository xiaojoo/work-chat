package com.chat.user.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_conversation")
public class Conversation {

    @Id
    private Long id;

    @Column(nullable = false)
    private Short type;

    /** 群聊才有；私聊改用 user_a_id / user_b_id 这一对，见类注释 */
    private Long targetId;

    /** 私聊双方，约定 a < b，配合 uk_private_pair 唯一索引当会话键 */
    @Column(name = "user_a_id")
    private Long userAId;

    @Column(name = "user_b_id")
    private Long userBId;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Short getType() { return type; }
    public void setType(Short type) { this.type = type; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getUserAId() { return userAId; }
    public void setUserAId(Long userAId) { this.userAId = userAId; }
    public Long getUserBId() { return userBId; }
    public void setUserBId(Long userBId) { this.userBId = userBId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
