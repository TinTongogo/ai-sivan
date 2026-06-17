-- ============================================================
-- V30：为 forest_nodes 添加记忆专用列（替代 JSONB 内存过滤）
-- ============================================================
-- 动机：
--   node_type='memory' 的节点将 level/archived/important/scopeId
--   等过滤字段存在 metadata JSONB 中，查询时全量加载后在 Java
--   中内存过滤（O(n)），无法走索引。
--
--   添加专用列 + 部分索引后，所有过滤映射到 SQL WHERE 子句
--   （O(log n)），metadata 仅保留真正的扩展属性。
-- ============================================================

ALTER TABLE forest_nodes
    ADD COLUMN IF NOT EXISTS level       VARCHAR(16),
    ADD COLUMN IF NOT EXISTS archived    BOOLEAN      DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS important   BOOLEAN      DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scope_id    UUID,
    ADD COLUMN IF NOT EXISTS summary     TEXT;

-- 按层级过滤（替代 JSONB 内存过滤）
CREATE INDEX IF NOT EXISTS idx_fn_memory_level
    ON forest_nodes (account_id, level)
    WHERE node_type = 'memory';

-- 仅未归档（遗忘曲线调度）
CREATE INDEX IF NOT EXISTS idx_fn_memory_archived
    ON forest_nodes (account_id, archived)
    WHERE node_type = 'memory' AND archived = FALSE;

-- 重要标记查询
CREATE INDEX IF NOT EXISTS idx_fn_memory_important
    ON forest_nodes (account_id, important)
    WHERE node_type = 'memory' AND important = TRUE;

-- 按 scope（对话/项目）过滤
CREATE INDEX IF NOT EXISTS idx_fn_memory_scope
    ON forest_nodes (scope_id)
    WHERE node_type = 'memory';

-- 遗忘曲线排序（保留率低优先归档）
CREATE INDEX IF NOT EXISTS idx_fn_memory_retention
    ON forest_nodes (retention)
    WHERE node_type = 'memory' AND archived = FALSE;
