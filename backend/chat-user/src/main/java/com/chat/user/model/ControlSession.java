package com.chat.user.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 远程控制会话总表。列必须和 deploy/postgres/init/01-schema.sql 的 chat_control_session 一一对应，
 * 本服务 ddl-auto=validate，字段漂移会让整个 chat-user 起不来。
 */
@Entity
@Table(name = "chat_control_session")
public class ControlSession {

    @Id
    private Long id;

    @Column(name = "initiator_id", nullable = false)
    private Long initiatorId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    /** 网关那一场控制的 id（cs-xxxx 十六进制串），不是主键、是业务自然键 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime;

    @Column(name = "accept_time")
    private LocalDateTime acceptTime;

    @Column(name = "stop_time")
    private LocalDateTime stopTime;

    @Column(name = "end_reason", length = 24)
    private String endReason;

    @Column(name = "duration_sec", nullable = false)
    private Integer durationSec = 0;

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
    protected void onUpdate() { updateTime = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInitiatorId() { return initiatorId; }
    public void setInitiatorId(Long initiatorId) { this.initiatorId = initiatorId; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getRequestTime() { return requestTime; }
    public void setRequestTime(LocalDateTime requestTime) { this.requestTime = requestTime; }
    public LocalDateTime getAcceptTime() { return acceptTime; }
    public void setAcceptTime(LocalDateTime acceptTime) { this.acceptTime = acceptTime; }
    public LocalDateTime getStopTime() { return stopTime; }
    public void setStopTime(LocalDateTime stopTime) { this.stopTime = stopTime; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String endReason) { this.endReason = endReason; }
    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
