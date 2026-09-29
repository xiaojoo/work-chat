package com.chat.message.service;

import com.chat.message.config.RabbitConfig;
import com.chat.message.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 把一条已落库的消息发到审计队列。
 *
 * 负载是竖线分隔的字符串而不是 JSON：这台机上 jackson 2 和 3 同时在类路径上，
 * 引哪个版本的转换器都是雷，而这种负载在管理台里能直接读出来，反倒好查。
 * 实时投递不依赖这条总线（设计文档第 22 章），所以发布失败只记日志、不影响发送。
 */
@Service
public class MessageAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(MessageAuditPublisher.class);

    /**
     * 投递到 broker 放到另一个线程：同步发的实测代价是 MQ 不可达时一次发送要等 5.15 秒
     * （connection-timeout 只管 socket 握手，spring-rabbit 的恢复还会再叠一层）。
     * 队列有界，满了就丢审计并记一条日志 —— 宁可少一条流水，也不能把用户的发消息拖住。
     */
    private static final int QUEUE_CAP = 2000;
    private final java.util.concurrent.ArrayBlockingQueue<String> queue =
            new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_CAP);
    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "audit-publish");
                t.setDaemon(true);
                return t;
            });

    private final RabbitTemplate rabbit;

    public MessageAuditPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
        sender.submit(this::drain);
    }

    private void drain() {
        while (true) {
            String payload;
            try {
                payload = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.AUDIT_ROUTING_KEY, payload);
            } catch (Exception e) {
                log.warn("audit publish failed, dropped: {} err={}", payload, e.toString());
            }
        }
    }

    public void publish(Message m) {
        String payload = m.getMessageId() + "|" + m.getConversationId() + "|" + m.getSenderId() + "|"
                + m.getMessageType() + "|" + bytesOf(m.getContent()) + "|" + System.currentTimeMillis();
        //  offer 失败 = 积压到上限，说明 broker 长时间不通；这里丢一条并喊出来
        if (!queue.offer(payload)) {
            log.warn("audit queue full ({}), dropped one: messageId={}", QUEUE_CAP, m.getMessageId());
        }
    }

    private static int bytesOf(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }
}
