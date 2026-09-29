package com.chat.message.repository;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 消息审计流水。按天分区（和 messages 表同样的道理：一天的量不能把分区撑爆）。
 * 只写不改不查详情，所以没有 where 条件以外的查询路径，count 走按天分区的聚合。
 */
@Repository
public class MessageAuditRepository {

    private final CqlSession session;
    private final PreparedStatement insertStmt;
    private final PreparedStatement countStmt;

    public MessageAuditRepository(CqlSession session) {
        this.session = session;
        this.insertStmt = session.prepare(
                "insert into message_audit (audit_date, audit_id, conversation_id, message_id, "
                        + "sender_id, message_type, content_bytes, create_time) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)");
        this.countStmt = session.prepare(
                "select count(*) from message_audit where audit_date = ?");
    }

    public void insert(LocalDate day, UUID auditId, String conversationId, String messageId,
                       long senderId, String messageType, int contentBytes, Instant at) {
        session.execute(insertStmt.bind(day, auditId, conversationId, messageId,
                senderId, messageType, contentBytes, at));
    }

    public long countOn(LocalDate day) {
        ResultSet rs = session.execute(countStmt.bind(day));
        Row r = rs.one();
        return r == null ? 0L : r.getLong(0);
    }
}
