-- 为 forest_nodes 添加对话必需字段，替代 forests 表承载对话功能

ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "account_id" UUID;
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "project_id" UUID;
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "created_at" TIMESTAMPTZ(6);

CREATE INDEX IF NOT EXISTS "idx_forest_nodes_conversation_account"
    ON "forest_nodes" ("account_id", "updated_at" DESC)
    WHERE "node_type" = 'conversation';
