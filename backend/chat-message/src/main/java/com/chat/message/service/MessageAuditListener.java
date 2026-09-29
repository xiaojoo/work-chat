package com.chat.message.service;

import com.chat.message.config.RabbitConfig;
import com.chat.message.repository.MessageAuditRepository;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 审计消费者：从队列里取一条就写一行流水。
 *
 * 消费失败不能把消息吞掉也不能无限重投：解析不了的（负载形状不对）记一条带原文的日志后丢弃，
 * 解析成功但 Cassandra 写失败的让 Rabbit 重新入队 —— 后者是真故障，前者是脏数据。
 */
@Service
public class MessageAuditListener {

    private static final Logger log = LoggerFactory.getLogger(MessageAuditListener.class);

    private final MessageAuditRepository audit;

    public MessageAuditListener(MessageAuditRepository audit) {
        this.audit = audit;
    }

    @RabbitListener(queues = RabbitConfig.AUDIT_QUEUE)
    public void onMessage(String payload) {
        String[] p = payload.split("\\|", 6);
        if (p.length < 6) {
            log.warn("audit payload malformed, dropped: {}", payload);
            return;
        }
        int bytes;
        try {
            bytes = Integer.parseInt(p[4]);
        } catch (NumberFormatException e) {
            log.warn("audit payload has a non-numeric byte count, dropped: {}", payload);
            return;
        }
        LocalDate today = LocalDate.now();
        audit.insert(today, Uuids.timeBased(), p[1], p[0], Long.parseLong(p[2]), p[3], bytes,
                java.time.Instant.now());
    }
}
