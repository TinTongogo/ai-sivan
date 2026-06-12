-- ============================================
-- V19__drop_agents_project_id.sql
-- project_id 对 agents 表无用，去除该列及相关约束
-- ============================================

-- 1. 先删除依赖 project_id 的外键约束
ALTER TABLE "agents" DROP CONSTRAINT IF EXISTS "agents_project_id_fkey";

-- 2. 删除索引（如有基于 project_id 的）
DROP INDEX IF EXISTS "idx_agents_project";

-- 3. 删除列
ALTER TABLE "agents" DROP COLUMN IF EXISTS "project_id";
