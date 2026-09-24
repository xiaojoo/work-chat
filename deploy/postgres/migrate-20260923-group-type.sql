-- 群聊类型：1 普通群 / 2 项目群 / 3 部门群（设计稿「创建群聊」的类型三选卡）
ALTER TABLE chat_group ADD COLUMN IF NOT EXISTS group_type SMALLINT NOT NULL DEFAULT 1;
