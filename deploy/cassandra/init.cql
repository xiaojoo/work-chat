CREATE KEYSPACE IF NOT EXISTS chat
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 1
};

USE chat;

-- 消息表 (按会话+日期分区)
CREATE TABLE IF NOT EXISTS messages (
    conversation_id text,
    message_date date,
    message_id timeuuid,
    sender_id bigint,
    message_type text,
    content text,
    extra text,
    create_time timestamp,
    PRIMARY KEY ((conversation_id, message_date), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);

-- 消息已读状态
CREATE TABLE IF NOT EXISTS message_read (
    conversation_id text,
    user_id bigint,
    last_read_message_id timeuuid,
    last_read_time timestamp,
    PRIMARY KEY (conversation_id, user_id)
);

DESCRIBE KEYSPACES;
-- 消息审计流水：由 RabbitMQ 的 chat.message.audit 消费者写入，按天分区
CREATE TABLE IF NOT EXISTS message_audit (
    audit_date date,
    audit_id timeuuid,
    conversation_id text,
    message_id text,
    sender_id bigint,
    message_type text,
    content_bytes int,
    create_time timestamp,
    PRIMARY KEY ((audit_date), audit_id)
) WITH CLUSTERING ORDER BY (audit_id DESC);

-- 送达回执：一条消息在哪些人的设备上落地了。已送达 != 已读，两件事两张表
CREATE TABLE IF NOT EXISTS message_delivered (
    conversation_id text,
    message_id text,
    user_id bigint,
    delivered_time timestamp,
    PRIMARY KEY ((conversation_id, message_id), user_id)
);

-- 发端 outbox 重放的持久去重：同一个 requestId 再来一次，直接把第一次存的那条还回去。
-- 网关那份 Redis 幂等只保 10 分钟，隔夜复活的 outbox 会躲过去、在库里留下双条；这张表没有期限。
-- 分区键 request_id 里存的是 "senderId:requestId"（不是光一个 requestId）：
-- requestId 由客户端自己编，两个人挑了同一个字符串就会撞车 —— 实测探针里两个人都用了 "u2"，
-- 第二个人消息被吃掉不说，还会拿到别人的那条当回执。作用域跟着人走才对。
-- 分区键 request_id，只做点查，行很窄。
CREATE TABLE IF NOT EXISTS message_request (
    request_id text,
    sender_id bigint,
    conversation_id text,
    message_date date,
    message_id timeuuid,
    created_at timestamp,
    PRIMARY KEY (request_id)
);
