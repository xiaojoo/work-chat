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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageService.class);

    /** 撤回窗口：发出 2 分钟内 */
    private static final java.time.Duration REVOKE_WINDOW = java.time.Duration.ofMinutes(2);
    private static final java.util.regex.Pattern FILE_REF =
            java.util.regex.Pattern.compile("\"fileId\":\"([0-9a-f]{32}\\.[a-z0-9]{1,8})\"");
    /** 补发最多往前翻几天：和分页加载的 7 天一个量级，留一天余量 */
    private static final int MAX_CATCHUP_DAYS = 8;
    /** "整天都要"的游标：Unix 0 时刻的 v1 timeuuid，比任何真实消息都早，又能绑进 TIMEUUID 列 */
    private static final java.util.UUID MIN_CURSOR = com.datastax.oss.driver.api.core.uuid.Uuids.startOf(0L);

    private final MessageRepository messageRepository;
    private final MessageReadRepository messageReadRepository;
    private final FileStorage fileStorage;
    private final MessageAuditPublisher auditPublisher;

    public MessageService(MessageRepository messageRepository,
                          MessageReadRepository messageReadRepository,
                          FileStorage fileStorage,
                          MessageAuditPublisher auditPublisher) {
        this.messageRepository = messageRepository;
        this.messageReadRepository = messageReadRepository;
        this.fileStorage = fileStorage;
        this.auditPublisher = auditPublisher;
    }

    /** 送达回执一次最多标这么多条：重连补报是合理的，一口报上来把服务打死不合理 */
    private static final int DELIVERED_BATCH_MAX = 200;

    public void markDelivered(String conversationId, List<String> messageIds, long userId) {
        int n = 0;
        for (String id : messageIds) {
            if (id == null || id.isBlank()) continue;
            if (n++ >= DELIVERED_BATCH_MAX) {
                return;
            }
            messageRepository.markDelivered(conversationId, id, userId);
        }
    }

    public Message saveMessage(SendMessageRequest request) {
        String reqId = request.getRequestId();
        boolean dedupable = reqId != null && !reqId.isBlank();
        if (dedupable) {
            // 重放：这条以前收过，把原样还回去，不再落第二条、也不再发审计事件
            Optional<Message> seen = messageRepository.findByRequestId(reqId, request.getSenderId());
            if (seen.isPresent()) {
                log.info("duplicate request ignored: requestId={} messageId={}", reqId, seen.get().getMessageId());
                return seen.get();
            }
        }

        Message msg = new Message();
        msg.setConversationId(request.getConversationId());
        msg.setSenderId(request.getSenderId());
        msg.setMessageType(request.getMessageType() != null ? request.getMessageType() : "TEXT");
        msg.setContent(request.getContent());
        if (request.getExtra() != null) {
            msg.setExtra(request.getExtra());
        }
        Message saved = messageRepository.save(msg);
        if (dedupable) {
            messageRepository.recordRequestId(reqId, saved.getSenderId(), saved);
        }
        // 落库成功之后才发审计事件：先 publish 再 save 的话，写库失败会在流水里留下一条没发生的消息
        auditPublisher.publish(saved);
        return saved;
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
     * 补发：游标之后、直到今天的消息，**正序**返回（客户端拿到就能直接往列表尾巴接）。
     * <p>
     * 分区键里带 message_date，而那个日期是 JVM 本地(+08)的日期、不是 UTC ——
     * 实测同一会话 44 条里有 1 条 create_time 的 UTC 日期和它的分区日期不同天。
     * 所以客户端必须把"这条落在哪个分区日期"和游标一起存回来，不能拿时间戳现推。
     * <p>
     * 补发窗口是有限的：游标日期超过 MAX_CATCHUP_DAYS 天就从窗口头开始，
     * 断线三十个月不该把整张表拖回一个客户端。
     */
    public List<Message> getMessagesAfter(String conversationId, UUID afterId,
                                          LocalDate cursorDate, int limit) {
        LocalDate today = LocalDate.now();
        LocalDate floor = today.minusDays(MAX_CATCHUP_DAYS);
        LocalDate start = cursorDate.isBefore(floor) ? floor : cursorDate;
        if (start.isAfter(today)) {
            // 客户端时钟跑到前面了：从今天整体补，别让它永远拿不到东西
            start = today;
        }

        // 驱动 4.x 按"值的版本"挑编解码器：version!=1 的 UUID（比如全 0 的 nil）绑到 TIMEUUID 列
        // 会抛 CodecNotFoundException，实测整个请求 500。"整天都要"就用驱动自带的 MIN_TIMEUUID。
        UUID from = afterId == null ? MIN_CURSOR : afterId;

        List<Message> out = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(today) && out.size() < limit; date = date.plusDays(1)) {
            // 只有游标自己那一天需要卡住起点，换到下一天就是整天都要
            UUID cursor = date.equals(cursorDate) ? from : MIN_CURSOR;
            List<Message> day = messageRepository.findByConversationIdAndDateAfter(
                    conversationId, date, cursor, limit - out.size());
            out.addAll(day);
        }
        return out;
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
