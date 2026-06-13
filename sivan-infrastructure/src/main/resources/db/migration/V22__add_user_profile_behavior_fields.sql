-- 16-用户画像与自适应: 增加行为追踪字段
ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS common_tasks TEXT[] DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS avg_complexity DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS active_hours INT[];
