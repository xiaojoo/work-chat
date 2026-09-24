-- 组织架构字段：设计稿「添加用户」的组织树、部门计数、成员列表与个人资料表单要用
ALTER TABLE chat_user ADD COLUMN IF NOT EXISTS department VARCHAR(64);
ALTER TABLE chat_user ADD COLUMN IF NOT EXISTS position VARCHAR(64);
ALTER TABLE chat_user ADD COLUMN IF NOT EXISTS email VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_user_department ON chat_user(department);
