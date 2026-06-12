-- V16：为 instinct_patterns 表添加 v2 字段
-- 对应 InstinctPattern 实体新增的 successRate/weight/draft

ALTER TABLE instinct_patterns
    ADD COLUMN IF NOT EXISTS success_rate double precision,
    ADD COLUMN IF NOT EXISTS weight double precision DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS draft boolean DEFAULT false;
