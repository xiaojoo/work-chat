package com.chat.message.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 异步任务总线。拓扑照 架构.txt 的名字：exchange chat.message.exchange，
 * 队列 persist / notify / audit。
 *
 * 这一版只声明并消费 audit（消息审计）。persist 的活（写 Cassandra）现在是同步做的，
 * 另开一条队列只会把同一件事做两遍；notify（离线推送）还没有真实的推送目标。
 * 那两个队列在 broker 上已经存在但**没有绑定**，所以不会堆积，等各自的消费者落地时再绑。
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "chat.message.exchange";
    public static final String AUDIT_QUEUE = "chat.message.audit";
    public static final String AUDIT_ROUTING_KEY = "message";

    @Bean
    public TopicExchange messageExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(AUDIT_QUEUE).build();
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange messageExchange) {
        return BindingBuilder.bind(auditQueue).to(messageExchange).with(AUDIT_ROUTING_KEY);
    }
}
