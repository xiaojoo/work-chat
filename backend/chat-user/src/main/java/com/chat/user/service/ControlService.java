package com.chat.user.service;

import com.chat.user.model.ControlEvent;
import com.chat.user.model.ControlSession;
import com.chat.user.repository.ControlEventRepository;
import com.chat.user.repository.ControlSessionRepository;
import com.chat.user.util.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程控制话单落库。所有 sessionId 参数都是网关 control.Session.ID 那串 cs-xxxx，
 * 不是本地主键；本地主键由 IdGenerator 生成，两者靠 findBySessionId 桥。
 *
 * 网关那边 request 和 accept/reject/stop/timeout 各自是独立 goroutine，两条并发到 chat-user 时
 * 都会想 INSERT 同一 sessionId。走 JPA save 撞 uk_ctl_session 会把整个事务标成 rollback-only
 * （catch 也救不回来）；所以建行统一走原生 INSERT ... ON CONFLICT DO NOTHING，
 * 谁先到谁建 PENDING，另一头 affected=0 就当作"已经存在"、继续走各自的终态更新。
 */
@Service
public class ControlService {

    private final ControlSessionRepository sessions;
    private final ControlEventRepository events;
    private final IdGenerator ids;
    private final JdbcTemplate jdbc;

    public ControlService(ControlSessionRepository sessions, ControlEventRepository events,
                          IdGenerator ids, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.events = events;
        this.ids = ids;
        this.jdbc = jdbc;
    }

    /** 建行只此一条路：PENDING + ON CONFLICT DO NOTHING。返回 true 表示这一头真的建了。 */
    private boolean ensurePending(String sessionId, long initiatorId, long targetId, String conversationId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int n = jdbc.update("INSERT INTO chat_control_session "
                        + "(id, initiator_id, target_id, conversation_id, session_id, status, request_time, "
                        + " duration_sec, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,0,?,?) ON CONFLICT (session_id) DO NOTHING",
                ids.nextId(), initiatorId, targetId, conversationId, sessionId, "PENDING", now, now, now);
        return n > 0;
    }

    /** 发起：PENDING 落一行 + REQUEST 事件。真建了才记事件，避免并发时写两条 REQUEST。 */
    public void request(String sessionId, long initiatorId, long targetId, String conversationId) {
        if (ensurePending(sessionId, initiatorId, targetId, conversationId)) {
            logEvent(sessionId, initiatorId, "REQUEST", LocalDateTime.now());
        }
    }

    public void accept(String sessionId, long byUserId, long initiatorId, long targetId, String conversationId) {
        ControlSession s = ensureAndLoad(sessionId, initiatorId, targetId, conversationId);
        if (s == null || !"PENDING".equals(s.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        s.setStatus("ACCEPTED");
        s.setAcceptTime(now);
        sessions.save(s);
        logEvent(sessionId, byUserId, "ACCEPT", now);
    }

    public void reject(String sessionId, long byUserId, long initiatorId, long targetId, String conversationId) {
        ControlSession s = ensureAndLoad(sessionId, initiatorId, targetId, conversationId);
        if (s == null) return;
        LocalDateTime now = LocalDateTime.now();
        s.setStatus("REJECTED");
        s.setStopTime(now);
        s.setEndReason("REJECTED");
        sessions.save(s);
        logEvent(sessionId, byUserId, "REJECT", now);
    }

    /** 停止：谁喊的都收摊。ACCEPTED 里 stop 才算"远程控制结束 00:xx"，PENDING 里 stop = 没人点头的取消。 */
    public void stop(String sessionId, long byUserId, long initiatorId, long targetId, String conversationId) {
        ControlSession s = ensureAndLoad(sessionId, initiatorId, targetId, conversationId);
        if (s == null) return;
        LocalDateTime now = LocalDateTime.now();
        boolean wasActive = "ACCEPTED".equals(s.getStatus());
        s.setStatus("STOPPED");
        s.setStopTime(now);
        s.setEndReason(wasActive ? "STOPPED" : "CANCELLED");
        s.setDurationSec(wasActive ? secondsBetween(s.getAcceptTime(), now) : 0);
        sessions.save(s);
        logEvent(sessionId, byUserId, wasActive ? "STOP" : "CANCEL", now);
    }

    /** 没人点头到点自动结束。已经被人答过的（ACCEPTED/REJECTED）不改。 */
    public void timeout(String sessionId, long initiatorId, long targetId, String conversationId) {
        ControlSession s = ensureAndLoad(sessionId, initiatorId, targetId, conversationId);
        if (s == null || !"PENDING".equals(s.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        s.setStatus("TIMED_OUT");
        s.setStopTime(now);
        s.setEndReason("TIMEOUT");
        sessions.save(s);
        logEvent(sessionId, initiatorId, "TIMEOUT", now);
    }

    /** 断线收场：ACCEPTED 才写时长；PENDING 断线只算"没人点头"，不叫结束 */
    public void disconnected(String sessionId, long byUserId, long initiatorId, long targetId, String conversationId) {
        ControlSession s = ensureAndLoad(sessionId, initiatorId, targetId, conversationId);
        if (s == null) return;
        LocalDateTime now = LocalDateTime.now();
        boolean wasActive = "ACCEPTED".equals(s.getStatus());
        s.setStatus("STOPPED");
        s.setStopTime(now);
        s.setEndReason("DISCONNECTED");
        s.setDurationSec(wasActive ? secondsBetween(s.getAcceptTime(), now) : 0);
        sessions.save(s);
        logEvent(sessionId, byUserId, "DISCONNECTED", now);
    }

    public List<Map<String, Object>> records(long userId, int limit) {
        List<ControlSession> list = sessions.findByUser(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ControlSession s : list) {
            if (out.size() >= limit) break;
            Map<String, Object> row = new HashMap<>();
            row.put("sessionId", s.getSessionId());
            row.put("initiatorId", s.getInitiatorId());
            row.put("targetId", s.getTargetId());
            row.put("status", s.getStatus());
            row.put("endReason", s.getEndReason());
            row.put("durationSec", s.getDurationSec());
            row.put("requestTime", s.getRequestTime() == null ? null : s.getRequestTime().toString());
            out.add(row);
        }
        return out;
    }

    /** 后置动作走到这一步：并发时 session 可能还没被 request 建，先补一行 PENDING，再读回。
     *  ensurePending 走原生 INSERT ON CONFLICT，不会把外层事务标成 rollback-only；
     *  读回用一条独立的 JPA 查询（当前事务里刚 INSERT 的行同事务能看见，别头的也已被别的提交落库）。 */
    private ControlSession ensureAndLoad(String sessionId, long initiatorId, long targetId, String conversationId) {
        ensurePending(sessionId, initiatorId, targetId, conversationId);
        return sessions.findBySessionId(sessionId).orElse(null);
    }

    private void logEvent(String sessionId, long actorId, String type, LocalDateTime at) {
        ControlEvent e = new ControlEvent();
        e.setId(ids.nextId());
        e.setSessionId(sessionId);
        e.setActorId(actorId);
        e.setEventType(type);
        e.setCreateTime(at);
        events.save(e);
    }

    private static int secondsBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null) return 0;
        long n = Duration.between(from, to).getSeconds();
        return (int) Math.max(0, n);
    }
}
