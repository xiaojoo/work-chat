package com.chat.message.repository;

import com.chat.message.model.MessageRead;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MessageReadRepository {

    private final CqlSession session;

    public MessageReadRepository(CqlSession session) {
        this.session = session;
    }

    public void save(MessageRead read) {
        PreparedStatement ps = session.prepare(
            "INSERT INTO message_read (conversation_id, user_id, last_read_message_id, last_read_time) " +
            "VALUES (?, ?, ?, ?)"
        );

        BoundStatement bs = ps.bind(
            read.getConversationId(),
            read.getUserId(),
            read.getLastReadMessageId(),
            read.getLastReadTime()
        );

        session.execute(bs);
    }

    public List<MessageRead> findByConversationId(String conversationId) {
        PreparedStatement ps = session.prepare(
            "SELECT * FROM message_read WHERE conversation_id = ?"
        );

        BoundStatement bs = ps.bind(conversationId);
        ResultSet rs = session.execute(bs);
        return mapRows(rs);
    }

    public Optional<MessageRead> findByConversationIdAndUserId(String conversationId, Long userId) {
        PreparedStatement ps = session.prepare(
            "SELECT * FROM message_read WHERE conversation_id = ? AND user_id = ?"
        );

        BoundStatement bs = ps.bind(conversationId, userId);
        ResultSet rs = session.execute(bs);
        List<MessageRead> results = mapRows(rs);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private List<MessageRead> mapRows(ResultSet rs) {
        List<MessageRead> list = new ArrayList<>();
        for (Row row : rs) {
            MessageRead read = new MessageRead();
            read.setConversationId(row.getString("conversation_id"));
            read.setUserId(row.getLong("user_id"));
            read.setLastReadMessageId(row.getUuid("last_read_message_id"));
            read.setLastReadTime(row.getInstant("last_read_time"));
            list.add(read);
        }
        return list;
    }
}
