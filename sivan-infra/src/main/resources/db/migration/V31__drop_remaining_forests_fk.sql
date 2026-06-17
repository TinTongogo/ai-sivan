-- ============================================================
-- V31：删除所有引用 forests 表的残余外键
-- ============================================================
-- 背景：
--   V29 仅删除 forest_nodes.forest_id → forests.forest_id 外键，
--   但 V6 创建的其他支持表仍持有对 forests.fk 的外键约束。
--   由于 forest_id 值现在使用 conversation UUID（forest_nodes 接管），
--   这些 FK 会导致 insert 失败（forest_id 不在 forests 表中）。
-- ============================================================

ALTER TABLE forest_hitl_reviews     DROP CONSTRAINT IF EXISTS fk_fhr_forest;
ALTER TABLE forest_artifacts         DROP CONSTRAINT IF EXISTS fk_fa_forest;
ALTER TABLE forest_agent_messages    DROP CONSTRAINT IF EXISTS fk_fam_forest;
ALTER TABLE forest_execution_logs    DROP CONSTRAINT IF EXISTS fk_fel_forest;
ALTER TABLE forest_schedule          DROP CONSTRAINT IF EXISTS fk_fs_forest;
