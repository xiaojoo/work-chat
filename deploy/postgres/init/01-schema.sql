-- 用户表
CREATE TABLE chat_user (
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

CREATE UNIQUE INDEX uk_username ON chat_user(username);

-- 好友关系
CREATE TABLE chat_friend (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    friend_id BIGINT NOT NULL,
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_friend ON chat_friend(user_id, friend_id);
CREATE INDEX idx_friend_user ON chat_friend(user_id);
CREATE INDEX idx_friend_target ON chat_friend(friend_id);

-- 群组
CREATE TABLE chat_group (
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

CREATE INDEX idx_group_owner ON chat_group(owner_id);

-- 群成员
CREATE TABLE chat_group_member (
    id BIGINT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role SMALLINT DEFAULT 0,
    mute_until TIMESTAMP,
    join_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_group_user ON chat_group_member(group_id, user_id);
CREATE INDEX idx_group_member_group ON chat_group_member(group_id);
CREATE INDEX idx_group_member_user ON chat_group_member(user_id);

-- 会话
CREATE TABLE chat_conversation (
    id BIGINT PRIMARY KEY,
    type SMALLINT NOT NULL,
    target_id BIGINT,
    user_a_id BIGINT,
    user_b_id BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_type_target ON chat_conversation(type, target_id) WHERE target_id IS NOT NULL;
CREATE UNIQUE INDEX uk_private_pair ON chat_conversation(user_a_id, user_b_id);

-- 用户会话
CREATE TABLE chat_user_conversation (
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

CREATE UNIQUE INDEX uk_user_conv ON chat_user_conversation(user_id, conversation_id);
CREATE INDEX idx_user_conv_user ON chat_user_conversation(user_id);

-- 通话话单。信令在 Go 网关，但通话这件事要留档：网关重启不再把通话记录抹掉，
-- 界面上的"通话记录"也才有地方读。
CREATE TABLE chat_call (
    id BIGINT PRIMARY KEY,
    room VARCHAR(80) NOT NULL,
    caller_id BIGINT NOT NULL,
    callee_id BIGINT NOT NULL,
    media_type VARCHAR(10) NOT NULL,
    status VARCHAR(16) NOT NULL,
    ring_time TIMESTAMP NOT NULL,
    connect_time TIMESTAMP,
    end_time TIMESTAMP,
    end_reason VARCHAR(24),
    duration_sec INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_call_room ON chat_call(room);
CREATE INDEX idx_call_caller ON chat_call(caller_id);

-- 每一通的参与者（含没接通的：joined_at 为空就是没进过房）
CREATE TABLE chat_call_member (
    id BIGINT PRIMARY KEY,
    call_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    joined_at TIMESTAMP,
    left_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_call_user ON chat_call_member(call_id, user_id);

-- 话单按人一条：未接、被拒、通完，是三个人不同的事实，不能塞进 chat_call 一列里
CREATE TABLE chat_call_record (
    id BIGINT PRIMARY KEY,
    call_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    media_type VARCHAR(10) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    duration_sec INT NOT NULL DEFAULT 0,
    end_reason VARCHAR(24),
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_call_record_user ON chat_call_record(user_id, create_time DESC);
