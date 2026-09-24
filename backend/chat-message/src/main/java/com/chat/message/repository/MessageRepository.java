package com.chat.message.repository;

import com.chat.message.model.Message;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class MessageRepository {

    private final CqlSession session;

    public MessageRepository(CqlSession session) {
        this.session = session;
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

        PreparedStatement ps = session.prepare(
            "INSERT INTO messages (conversation_id, message_date, message_id, sender_id, message_type, content, extra, create_time) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        );

        BoundStatement bs = ps.bind(
            msg.getConversationId(),
            msg.getMessageDate(),
            msg.getMessageId(),
            msg.getSenderId(),
            msg.getMessageType(),
            msg.getContent(),
            msg.getExtra(),
            msg.getCreateTime()
        );

        session.execute(bs);
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
