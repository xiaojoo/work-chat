-- 个人资料弹窗的「个人简介」：稿子里 0/200 的多行文本，落在 chat_user.bio
ALTER TABLE chat_user ADD COLUMN IF NOT EXISTS bio VARCHAR(200);
