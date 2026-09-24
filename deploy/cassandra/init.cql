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