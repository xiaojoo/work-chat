-- ============================================================
-- 2026-09-22 私聊会话键：算术编码 → (user_a_id, user_b_id) 一对
-- 原因：min*10^9+max 在 16 位雪花 userId 上溢出 long，实测得到
--       target_id = -8377299863974971000，且回绕后不同用户对会撞键。
-- 已经按新版 init SQL 建库的不用跑这个；老库跑一遍即可。
-- ============================================================

ALTER TABLE chat_conversation ALTER COLUMN target_id DROP NOT NULL;
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS user_a_id BIGINT;
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS user_b_id BIGINT;

-- 回填：私聊行的两个用户从绑定表里取，保证 a < b
UPDATE chat_conversation c
   SET user_a_id = p.a, user_b_id = p.b
  FROM (
        SELECT uc.conversation_id, MIN(uc.user_id) AS a, MAX(uc.user_id) AS b
          FROM chat_user_conversation uc
          JOIN chat_conversation cc ON cc.id = uc.conversation_id
         WHERE cc.type = 1 AND cc.user_a_id IS NULL
         GROUP BY uc.conversation_id
        HAVING COUNT(DISTINCT uc.user_id) = 2
       ) p
 WHERE c.id = p.conversation_id AND c.type = 1;

-- 旧的溢出编码不再当唯一键用（群聊的 target_id 保持不动）
UPDATE chat_conversation SET target_id = NULL WHERE type = 1;

DROP INDEX IF EXISTS uk_type_target;
CREATE UNIQUE INDEX IF NOT EXISTS uk_type_target ON chat_conversation(type, target_id) WHERE target_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_private_pair ON chat_conversation(user_a_id, user_b_id);
