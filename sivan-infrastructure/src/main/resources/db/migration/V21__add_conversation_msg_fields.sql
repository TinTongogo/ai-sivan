-- 14-对话管理: 增加 Conversation status 字段
ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'ACTIVE';

-- 14-对话管理: 增加 Message msg_type 和 importance 字段
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS msg_type VARCHAR(16) DEFAULT 'normal',
    ADD COLUMN IF NOT EXISTS importance DOUBLE PRECISION DEFAULT 0.0;
