package com.chat.user.service;

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
 * 通话话单。用 JdbcTemplate 而不是 JPA 实体：这三张表是只写流水、只有一条读路径，
 * 而本服务是 ddl-auto=validate —— 实体和列一旦漂移，chat-user 整个起不来。
 */
@Service
public class CallService {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public CallService(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /** 主叫发起：写一通 RINGING + 两个参与者。重复发起同一个 room 不重复建行 */
    public Long start(String room, long callerId, long calleeId, String mediaType) {
        List<Long> existing = jdbc.query(
                "select id from chat_call where room = ?", (rs, i) -> rs.getLong(1), room);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        long id = ids.nextId();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("insert into chat_call (id, room, caller_id, callee_id, media_type, status, "
                        + "ring_time, duration_sec, create_time, update_time) "
                        + "values (?,?,?,?,?,?,?,?,?,?)",
                id, room, callerId, calleeId, mediaType, "RINGING", now, 0, now, now);
        for (Object[] m : new Object[][]{{callerId, "CALLER"}, {calleeId, "CALLEE"}}) {
            jdbc.update("insert into chat_call_member (id, call_id, user_id, direction, create_time) "
                            + "values (?,?,?,?,?)",
                    ids.nextId(), id, m[0], m[1], now);
        }
        return id;
    }

    /** 应答：谁应了就把他标成已进房；第一方进房时整通转 CONNECTED */
    public void connect(String room, long userId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Long id = roomId(room);
        if (id == null) return;
        jdbc.update("update chat_call_member set joined_at = ? where call_id = ? and user_id = ? "
                + "and joined_at is null", now, id, userId);
        jdbc.update("update chat_call set status = 'CONNECTED', connect_time = coalesce(connect_time, ?), "
                + "update_time = ? where id = ? and status <> 'CONNECTED'", now, now, id);
    }

    /**
     * 收线：写时长，并给两个人各落一条话单。
     * outcome 是"站在这个人角度看这通算什么"，不是通话本身的状态 ——
     * 主叫没等到人、被叫没接、被拒、中途断网，四种事实对两个人的意义各不相同。
     */
    public Map<String, Object> end(String room, long byUserId, String reason) {
        Long id = roomId(room);
        if (id == null) {
            return Map.of("error", "NO_SUCH_CALL", "room", room);
        }
        Map<String, Object> call = jdbc.queryForMap(
                "select caller_id, callee_id, media_type, connect_time, ring_time, status "
                        + "from chat_call where id = ?", id);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        boolean connected = call.get("connect_time") != null;
        Timestamp from = connected
                ? Timestamp.valueOf(call.get("connect_time").toString())
                : Timestamp.valueOf(call.get("ring_time").toString());
        int duration = connected ? (int) Duration.between(from.toLocalDateTime(), now.toLocalDateTime()).getSeconds() : 0;

        jdbc.update("update chat_call set status = 'ENDED', end_time = ?, end_reason = ?, "
                + "duration_sec = ?, update_time = ? where id = ?",
                now, reason, duration, now, id);
        jdbc.update("update chat_call_member set left_at = ? where call_id = ? and left_at is null", now, id);

        long callerId = ((Number) call.get("caller_id")).longValue();
        long calleeId = ((Number) call.get("callee_id")).longValue();
        String media = (String) call.get("media_type");
        if (!"ENDED".equals(String.valueOf(call.get("status")))) {
            for (long who : new long[]{callerId, calleeId}) {
                if (recordExists(id, who)) continue;
                jdbc.update("insert into chat_call_record (id, call_id, user_id, peer_id, media_type, "
                                + "outcome, duration_sec, end_reason, create_time) values (?,?,?,?,?,?,?,?,?)",
                        ids.nextId(), id, who, who == callerId ? calleeId : callerId, media,
                        outcome(who, callerId, calleeId, connected, reason), duration, reason, now);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("room", room);
        out.put("callId", id);
        out.put("durationSec", duration);
        out.put("connected", connected);
        return out;
    }

    private boolean recordExists(long callId, long userId) {
        Integer n = jdbc.queryForObject("select count(*) from chat_call_record where call_id = ? and user_id = ?",
                Integer.class, callId, userId);
        return n != null && n > 0;
    }

    private static String outcome(long who, long callerId, long calleeId, boolean connected, String reason) {
        if (connected) return "ENDED";
        boolean caller = who == callerId;
        switch (reason == null ? "" : reason) {
            case "REJECT":
                return "REJECTED";
            case "DISCONNECTED":
                return caller ? "DISCONNECTED" : "MISSED";
            case "HANGUP":
                return caller ? "CANCELLED" : "MISSED";
            default:
                return caller ? "NO_ANSWER" : "MISSED";
        }
    }

    private Long roomId(String room) {
        List<Long> ids = jdbc.query("select id from chat_call where room = ?", (rs, i) -> rs.getLong(1), room);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 我的通话记录，新的在前 */
    public List<Map<String, Object>> records(long userId, int limit) {
        int n = limit <= 0 || limit > 100 ? 50 : limit;
        List<Map<String, Object>> rows = jdbc.query(
                "select r.id, r.call_id, r.peer_id, r.media_type, r.outcome, r.duration_sec, "
                        + "r.end_reason, r.create_time, c.room "
                        + "from chat_call_record r join chat_call c on c.id = r.call_id "
                        + "where r.user_id = ? order by r.create_time desc limit ?",
                (rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("callId", rs.getLong("call_id"));
                    m.put("peerId", rs.getLong("peer_id"));
                    m.put("mediaType", rs.getString("media_type"));
                    m.put("outcome", rs.getString("outcome"));
                    m.put("durationSec", rs.getInt("duration_sec"));
                    m.put("endReason", rs.getString("end_reason"));
                    m.put("createTime", rs.getTimestamp("create_time").toString());
                    m.put("room", rs.getString("room"));
                    return m;
                }, userId, n);
        return new ArrayList<>(rows);
    }
}
