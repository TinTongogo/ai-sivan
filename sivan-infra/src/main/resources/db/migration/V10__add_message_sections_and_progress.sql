-- ============================================
-- V3: 消息表新增编排阶段字段
-- sections: 编排阶段详情 JSONB（持久化）
-- progress: 运行时进度状态 JSONB（流完成后清除）
-- audios: 音频列表 JSON 数组
-- ============================================

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS audios text COLLATE "pg_catalog"."default";

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS sections jsonb;

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS progress jsonb;
