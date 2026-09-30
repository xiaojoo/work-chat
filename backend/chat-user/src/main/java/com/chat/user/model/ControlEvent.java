package com.chat.user.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 远程控制事件日志。列必须和 deploy/postgres/init/01-schema.sql 的 chat_control_event 一一对应，
 * 因为本服务是 ddl-auto=validate —— 少一列多一列都整个起不来。
 */
@Entity
@Table(name = "chat_control_event")
public class ControlEvent {

    @Id
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
