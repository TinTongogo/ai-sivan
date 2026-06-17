-- 移除 forest_nodes.forest_id → forests.forest_id 外键约束
-- forests 表即将废弃，所有数据迁移到 forest_nodes

ALTER TABLE forest_nodes DROP CONSTRAINT IF EXISTS forest_nodes_forest_id_fkey;

-- 标记 forests 表为废弃（暂不删除，逐步移除代码依赖后清理）
