-- ============================================
-- V3: Forest 上下文持久化
-- ============================================

-- 森林聚合根
CREATE TABLE IF NOT EXISTS "forests" (
    "forest_id"    UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "account_id"   UUID        NOT NULL,
    "project_id"   UUID,
    "title"        VARCHAR(256) NOT NULL,
    "root_node_id" VARCHAR     NOT NULL,
    "created_at"   TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    "updated_at"   TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "forests_account_id_fkey"
        FOREIGN KEY ("account_id") REFERENCES "accounts"("account_id")
        ON DELETE CASCADE,
    CONSTRAINT "forests_project_id_fkey"
        FOREIGN KEY ("project_id") REFERENCES "projects"("project_id")
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS "idx_forests_account" ON "forests" ("account_id");
CREATE INDEX IF NOT EXISTS "idx_forests_project" ON "forests" ("project_id");

-- 森林节点（递归树结构）
CREATE TABLE IF NOT EXISTS "forest_nodes" (
    "node_id"        VARCHAR     NOT NULL PRIMARY KEY,
    "forest_id"      UUID        NOT NULL,
    "node_type"      VARCHAR(16) NOT NULL,   -- task / inner_goal / synthesis / message / memory
    "parent_node_id" VARCHAR,                -- NULL 表示根节点
    "sort_order"     INT         NOT NULL DEFAULT 0,
    "mode"           VARCHAR(16),            -- 仅 ExecutableNode: NONE / SEQUENTIAL / PARALLEL / CONDITIONAL / HIERARCHICAL / CONSENSUS
    "status"         VARCHAR(16),            -- 仅 ExecutableNode: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED
    "content"        TEXT,                   -- 仅 ContentNode
    "metadata"       JSONB       DEFAULT '{}'::jsonb,  -- 仅 ContentNode
    "importance"     DOUBLE PRECISION,       -- 仅 CompressibleNode
    "estimate_tokens" BIGINT,                -- 仅 CompressibleNode
    "updated_at"     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "forest_nodes_forest_id_fkey"
        FOREIGN KEY ("forest_id") REFERENCES "forests"("forest_id")
        ON DELETE CASCADE,
    CONSTRAINT "forest_nodes_parent_node_id_fkey"
        FOREIGN KEY ("parent_node_id") REFERENCES "forest_nodes"("node_id")
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_forest_nodes_forest" ON "forest_nodes" ("forest_id");
CREATE INDEX IF NOT EXISTS "idx_forest_nodes_parent" ON "forest_nodes" ("parent_node_id");
CREATE INDEX IF NOT EXISTS "idx_forest_nodes_forest_parent" ON "forest_nodes" ("forest_id", "parent_node_id");
