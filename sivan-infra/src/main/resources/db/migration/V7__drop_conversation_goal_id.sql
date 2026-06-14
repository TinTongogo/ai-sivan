-- ============================================
-- V7: 清理 V1 遗留列
-- goal_id 已由 Forest 架构替代
-- ============================================

ALTER TABLE conversations DROP COLUMN IF EXISTS goal_id;
