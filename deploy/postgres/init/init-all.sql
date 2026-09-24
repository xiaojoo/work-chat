-- ================================================
-- Chat System 完整初始化脚本
-- 使用方式: psql -U chat -d chat -f init-all.sql
-- ================================================

-- 用户表
CREATE TABLE IF NOT EXISTS chat_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    avatar VARCHAR(512),
    phone VARCHAR(32),
    location VARCHAR(128),
    remark VARCHAR(256),
    department VARCHAR(64),
    position VARCHAR(64),
    email VARCHAR(128),
    bio VARCHAR(200),
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_username ON chat_user(username);

-- 好友关系
CREATE TABLE IF NOT EXISTS chat_friend (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    friend_id BIGINT NOT NULL,
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_friend ON chat_friend(user_id, friend_id);
CREATE INDEX IF NOT EXISTS idx_friend_user ON chat_friend(user_id);
CREATE INDEX IF NOT EXISTS idx_friend_target ON chat_friend(friend_id);

-- 群组
CREATE TABLE IF NOT EXISTS chat_group (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    avatar VARCHAR(512),
    owner_id BIGINT NOT NULL,
    announcement VARCHAR(1000),
    group_type SMALLINT NOT NULL DEFAULT 1,
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_owner ON chat_group(owner_id);

-- 群成员
CREATE TABLE IF NOT EXISTS chat_group_member (
    id BIGINT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role SMALLINT DEFAULT 0,
    mute_until TIMESTAMP,
    join_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_group_user ON chat_group_member(group_id, user_id);
CREATE INDEX IF NOT EXISTS idx_group_member_group ON chat_group_member(group_id);
CREATE INDEX IF NOT EXISTS idx_group_member_user ON chat_group_member(user_id);

-- 会话
CREATE TABLE IF NOT EXISTS chat_conversation (
    id BIGINT PRIMARY KEY,
    type SMALLINT NOT NULL,
    target_id BIGINT,
    user_a_id BIGINT,
    user_b_id BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 群聊按 (type, 群ID) 唯一；私聊按 (小ID, 大ID) 这一对唯一。
-- 私聊不再用 min*10^9+max 算术编码：userId 是 16 位，乘完溢出 long 会回绕碰撞。
CREATE UNIQUE INDEX IF NOT EXISTS uk_type_target ON chat_conversation(type, target_id) WHERE target_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_private_pair ON chat_conversation(user_a_id, user_b_id);

-- 用户会话
CREATE TABLE IF NOT EXISTS chat_user_conversation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    last_message_id VARCHAR(255),
    last_message_time TIMESTAMP,
    unread_count INT DEFAULT 0,
    deleted BOOLEAN DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_conv ON chat_user_conversation(user_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_user_conv_user ON chat_user_conversation(user_id);
