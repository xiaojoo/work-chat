package com.chat.message.repository;

import com.chat.message.model.Message;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    private final CqlSession session;
    private volatile boolean firstWriteLogged = false;
    /**
     * 原来这个 prepare 写在 save() 里 —— 每条消息都要多跑一次"预备语句"的网络往返。
     * 挪到构造期，和 MessageAuditRepository 同一个口径。
     */
    private final PreparedStatement insertStmt;
    private final PreparedStatement deliveredStmt;

    public MessageRepository(CqlSession session) {
        this.session = session;
        this.insertStmt = session.prepare(
            "INSERT INTO messages (conversation_id, message_date, message_id, sender_id, message_type, content, extra, create_time) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        );
        this.deliveredStmt = session.prepare(
            "INSERT INTO message_delivered (conversation_id, message_id, user_id, delivered_time) " +
            "VALUES (?, ?, ?, ?)"
        );
    }

    /** 某条消息在某个人的设备上落地了。主键里有 user_id，同一人重报同一条只覆盖一行。 */
    public void markDelivered(String conversationId, String messageId, long userId) {
        session.execute(deliveredStmt.bind(conversationId, messageId, userId, Instant.now()));
    }

    public Message save(Message msg) {
        if (msg.getMessageId() == null) {
            msg.setMessageId(Uuids.timeBased());
        }
        if (msg.getCreateTime() == null) {
            msg.setCreateTime(Instant.now());
        }
        if (msg.getMessageDate() == null) {
            msg.setMessageDate(LocalDate.now());
        }

        BoundStatement bs = insertStmt.bind(
            msg.getConversationId(),
            msg.getMessageDate(),
            msg.getMessageId(),
            msg.getSenderId(),
            msg.getMessageType(),
            msg.getContent(),
            msg.getExtra(),
            msg.getCreateTime()
        );

        long t0 = System.nanoTime();
        session.execute(bs);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // 慢写要能自己喊出来；第一笔额外无条件记一条，因为"进程内第一次慢"这种问题
        // 只有那一次有信息量，不开 DEBUG 就永远抓不到
        if (ms > 500 || !firstWriteLogged) {
            firstWriteLogged = true;
            log.warn("insert timing: {} ms msgId={}", ms, msg.getMessageId());
        }
        return msg;
    }

    public List<Message> findByConversationIdAndDate(String conversationId, LocalDate date, int limit) {
        PreparedStatement ps = session.prepare(
            "SELECT * FROM messages WHERE conversation_id = ? AND message_date = ? ORDER BY message_id DESC LIMIT ?"
        );

        BoundStatement bs = ps.bind(conversationId, date, limit);
        ResultSet rs = session.execute(bs);
        return mapRows(rs);
    }

    /** 主键全给齐的精确读：message_id 是聚簇键，不用扫整个分区 */
    public java.util.Optional<Message> findById(String conversationId, LocalDate date, UUID messageId) {
        PreparedStatement ps = session.prepare(
            "SELECT * FROM messages WHERE conversation_id = ? AND message_date = ? AND message_id = ?"
        );

        ResultSet rs = session.execute(ps.bind(conversationId, date, messageId));
        List<Message> rows = mapRows(rs);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    public List<Message> findByConversationIdAndDateBefore(String conversationId, LocalDate date, UUID beforeId, int limit) {
        PreparedStatement ps = session.prepare(
            "SELECT * FROM messages WHERE conversation_id = ? AND message_date = ? AND message_id < ? ORDER BY message_id DESC LIMIT ?"
        );

        BoundStatement bs = ps.bind(conversationId, date, beforeId, limit);
        ResultSet rs = session.execute(bs);
        return mapRows(rs);
    }

    /** 补发用：那一天这个分区里、游标之后的条目，按时间正序。
     *  游标给 nil UUID（全 0）就是整天全部 —— 分区键里有 message_date，换天时不需要旧游标 */
    public List<Message> findByConversationIdAndDateAfter(String conversationId, LocalDate date, UUID afterId, int limit) {
        PreparedStatement ps = session.prepare(
            "SELECT * FROM messages WHERE conversation_id = ? AND message_date = ? AND message_id > ? ORDER BY message_id ASC LIMIT ?"
        );

        BoundStatement bs = ps.bind(conversationId, date, afterId, limit);
        ResultSet rs = session.execute(bs);
        return mapRows(rs);
    }

    /**
     * 发端重放的去重账：这个人在这一笔 requestId 上落到哪条消息。
     * 键是 {@code senderId:requestId} —— 不是光一个 requestId：
     * 客户端自己编 id，两个不同的人挑了同一个字符串（实测探针里就是两个人都用 "u2"）
     * 就会撞在一起，第二个人不仅消息被吃掉，还会拿到**别人的那条**当回执。
     * requestId 的作用域从来都是"某个客户端自己"，所以账也得按人分。
     */
    private static String scopedKey(long senderId, String requestId) {
        return senderId + ":" + requestId;
    }

    public java.util.Optional<Message> findByRequestId(String requestId, long senderId) {
        PreparedStatement ps = session.prepare(
            "SELECT conversation_id, message_date, message_id FROM message_request WHERE request_id = ?"
        );
        Row r = session.execute(ps.bind(scopedKey(senderId, requestId))).one();
        if (r == null) {
            return java.util.Optional.empty();
        }
        return findById(r.getString("conversation_id"), r.getLocalDate("message_date"), r.getUuid("message_id"));
    }

    /**
     * 记下这个 requestId 存成了哪条。普通写（不是 LWT）：
     * 同一瞬间并发重发由网关那层 Redis SETNX 挡住，这张表管的是"隔了很久再来"的那一种。
     */
    public void recordRequestId(String requestId, long senderId, Message msg) {
        PreparedStatement ps = session.prepare(
            "INSERT INTO message_request (request_id, sender_id, conversation_id, message_date, message_id, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)"
        );
        session.execute(ps.bind(scopedKey(senderId, requestId), senderId, msg.getConversationId(),
                msg.getMessageDate(), msg.getMessageId(), Instant.now()));
    }

    private List<Message> mapRows(ResultSet rs) {
        List<Message> messages = new ArrayList<>();
        for (Row row : rs) {
            Message msg = new Message();
            msg.setConversationId(row.getString("conversation_id"));
            msg.setMessageDate(row.getLocalDate("message_date"));
            msg.setMessageId(row.getUuid("message_id"));
            msg.setSenderId(row.getLong("sender_id"));
            msg.setMessageType(row.getString("message_type"));
            msg.setContent(row.getString("content"));
            msg.setExtra(row.getString("extra"));
            msg.setCreateTime(row.getInstant("create_time"));
            messages.add(msg);
        }
        return messages;
    }
}
