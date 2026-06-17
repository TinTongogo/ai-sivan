-- forest_nodes 统一存储：消息、记忆合入，知识库暂留独立

DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS conversations;
DROP TABLE IF EXISTS memory_entries;

ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "role" VARCHAR(16);
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "vector" vector(1024);
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "retention" NUMERIC(5,4);
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "access_count" INT DEFAULT 0;
ALTER TABLE forest_nodes ADD COLUMN IF NOT EXISTS "last_accessed_at" TIMESTAMPTZ(6);

ALTER TABLE forests DROP COLUMN IF EXISTS "conversation_id";

CREATE INDEX IF NOT EXISTS "idx_forest_nodes_type" ON "forest_nodes" ("node_type");
CREATE INDEX IF NOT EXISTS "idx_forest_nodes_type_sort" ON "forest_nodes" ("forest_id", "node_type", "sort_order");
CREATE INDEX IF NOT EXISTS "idx_forest_nodes_memory_vector" ON "forest_nodes"
    USING hnsw ("vector" vector_cosine_ops) WHERE "node_type" = 'memory';
