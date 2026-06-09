-- ============================================
-- V5: 清理 V1 Goal/Pipeline 表
-- V2 Forest 架构已完全替代，安全移除
-- ============================================

DROP TABLE IF EXISTS goal_artifacts CASCADE;
DROP TABLE IF EXISTS goal_tasks CASCADE;
DROP TABLE IF EXISTS goal_milestones CASCADE;
DROP TABLE IF EXISTS pipeline_steps CASCADE;
DROP TABLE IF EXISTS goals CASCADE;
