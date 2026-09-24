package com.chat.group.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_group")
public class Group {

    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 512)
    private String avatar;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(length = 1000)
    private String announcement;

    /** 1 普通群 / 2 项目群 / 3 部门群 */
    @Column(name = "group_type", nullable = false)
    private Short groupType = 1;

    @Column(nullable = false)
    private Short status = 1;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getAnnouncement() { return announcement; }
    public void setAnnouncement(String announcement) { this.announcement = announcement; }
    public Short getGroupType() { return groupType; }
    public void setGroupType(Short groupType) { this.groupType = groupType; }
    public Short getStatus() { return status; }
    public void setStatus(Short status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
