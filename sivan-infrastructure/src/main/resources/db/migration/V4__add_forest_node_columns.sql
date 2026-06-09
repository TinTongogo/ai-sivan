-- ============================================
-- V4: 补充 forest_nodes 字段
-- 对齐设计文档 §1.3 DDL
-- ============================================

-- kind: TEMPLATE / INSTANCE（默认 INSTANCE）
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "kind" VARCHAR(16) NOT NULL DEFAULT 'INSTANCE';

-- content_hash: 增量更新检测（SHA-256 前 64 字符）
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "content_hash" VARCHAR(64);

-- completed_at: 节点完成时间
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "completed_at" TIMESTAMPTZ(6);
