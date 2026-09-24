package com.chat.message.service;

import com.chat.message.dto.SendMessageRequest;
import com.chat.message.model.Message;
import com.chat.message.model.MessageRead;
import com.chat.message.repository.MessageReadRepository;
import com.chat.message.repository.MessageRepository;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class MessageService {

    /** 撤回窗口：发出 2 分钟内 */
    private static final java.time.Duration REVOKE_WINDOW = java.time.Duration.ofMinutes(2);
    private static final java.util.regex.Pattern FILE_REF =
            java.util.regex.Pattern.compile("\"fileId\":\"([0-9a-f]{32}\\.[a-z0-9]{1,8})\"");

    private final MessageRepository messageRepository;
    private final MessageReadRepository messageReadRepository;
    private final FileStorage fileStorage;

    public MessageService(MessageRepository messageRepository,
                          MessageReadRepository messageReadRepository,
                          FileStorage fileStorage) {
        this.messageRepository = messageRepository;
        this.messageReadRepository = messageReadRepository;
        this.fileStorage = fileStorage;
    }

    public Message saveMessage(SendMessageRequest request) {
        Message msg = new Message();
        msg.setConversationId(request.getConversationId());
        msg.setSenderId(request.getSenderId());
        msg.setMessageType(request.getMessageType() != null ? request.getMessageType() : "TEXT");
        msg.setContent(request.getContent());
        if (request.getExtra() != null) {
            msg.setExtra(request.getExtra());
        }
        return messageRepository.save(msg);
    }

    /**
     * 获取最新消息
     */
    public List<Message> getMessages(String conversationId, int limit) {
        LocalDate today = LocalDate.now();
        List<Message> todayMessages = messageRepository
                .findByConversationIdAndDate(conversationId, today, limit);

        if (todayMessages.size() >= limit) {
            return todayMessages;
        }

        // 如果今天消息不够，往前翻
        List<Message> allMessages = new ArrayList<>(todayMessages);
        LocalDate date = today.minusDays(1);
        int remaining = limit - allMessages.size();
        int maxDays = 7;

        for (int i = 0; i < maxDays && remaining > 0; i++) {
            List<Message> dayMessages = messageRepository
                    .findByConversationIdAndDate(conversationId, date, remaining);
            if (!dayMessages.isEmpty()) {
                allMessages.addAll(dayMessages);
                remaining = limit - allMessages.size();
            }
            date = date.minusDays(1);
        }

        return allMessages;
    }

    /**
     * 分页加载更早的消息
     */
    public List<Message> getMessagesBefore(String conversationId, UUID beforeMessageId,
                                            LocalDate messageDate, int limit) {
        List<Message> messages = messageRepository.findByConversationIdAndDateBefore(
                conversationId, messageDate, beforeMessageId, limit);

        if (messages.size() >= limit) {
            return messages;
        }

        List<Message> result = new ArrayList<>(messages);
        LocalDate date = messageDate.minusDays(1);
        int remaining = limit - result.size();
        int maxDays = 7;

        for (int i = 0; i < maxDays && remaining > 0; i++) {
            List<Message> dayMessages = messageRepository
                    .findByConversationIdAndDate(conversationId, date, remaining);
            if (!dayMessages.isEmpty()) {
                result.addAll(dayMessages);
                remaining = limit - result.size();
            }
            date = date.minusDays(1);
        }

        return result;
    }

    /**
     * 标记消息已读
     */
    public void markAsRead(String conversationId, Long userId, UUID lastReadMessageId) {
        Optional<MessageRead> existing = messageReadRepository
                .findByConversationIdAndUserId(conversationId, userId);

        MessageRead read;
        if (existing.isPresent()) {
            read = existing.get();
            read.setLastReadMessageId(lastReadMessageId);
            read.setLastReadTime(Instant.now());
        } else {
            read = new MessageRead(conversationId, userId, lastReadMessageId);
        }
        messageReadRepository.save(read);
    }

    /**
     * 删除消息（软删除，标记内容为空）
     */
    /**
     * 撤回。归属、时间窗都在服务端判 —— 客户端的菜单只是入口，不能当门禁。
     */
    public Revoke revoke(String conversationId, UUID messageId, Long userId) {
        Message target = findMessage(conversationId, messageId);
        if (target == null) {
            return Revoke.NOT_FOUND;
        }
        if (!userId.equals(target.getSenderId())) {
            return Revoke.NOT_OWNER;
        }
        Instant created = target.getCreateTime() != null
                ? target.getCreateTime()
                : Instant.ofEpochMilli(Uuids.unixTimestamp(messageId));
        if (Duration.between(created, Instant.now()).compareTo(REVOKE_WINDOW) > 0) {
            return Revoke.TOO_LATE;
        }

        String previous = target.getContent();
        target.setContent("该消息已撤回");
        target.setMessageType("DELETED");
        messageRepository.save(target);

        // 撤回的是文件/图片消息时，对象留在桶里就再没人引用了，成为孤儿
        java.util.regex.Matcher m = FILE_REF.matcher(previous == null ? "" : previous);
        if (m.find()) {
            try {
                fileStorage.delete(m.group(1));
            } catch (Exception e) {
                System.err.println("撤回后删除对象失败 " + m.group(1) + ": " + e.getMessage());
            }
        }
        return Revoke.OK;
    }

    /** timeuuid 自带写入时刻，反推出分区日期，最多再看前后一天兜底；不再扫 8 天 × 1000 条 */
    private Message findMessage(String conversationId, UUID messageId) {
        LocalDate day = Instant.ofEpochMilli(Uuids.unixTimestamp(messageId))
                .atZone(ZoneId.systemDefault()).toLocalDate();
        for (int i = 0; i <= 2; i++) {
            LocalDate probe = i == 0 ? day : (i == 1 ? day.minusDays(1) : day.plusDays(1));
            Optional<Message> hit = messageRepository.findById(conversationId, probe, messageId);
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        return null;
    }

    public enum Revoke { OK, NOT_FOUND, NOT_OWNER, TOO_LATE }

    /**
     * 获取会话中各用户的已读状态
     */
    public List<MessageRead> getReadStatus(String conversationId) {
        return messageReadRepository.findByConversationId(conversationId);
    }
}
