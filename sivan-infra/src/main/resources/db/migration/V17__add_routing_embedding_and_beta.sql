-- V17：路由系统升级 — 向量检索 + 贝叶斯参数
-- 1. routing_decisions 表新增 embedding 列
-- 2. 新建 account_beta_params 表

-- 需要先创建 pgvector 扩展（如未启用）
-- CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE routing_decisions
    ADD COLUMN IF NOT EXISTS task_embedding vector(1024),
    ADD COLUMN IF NOT EXISTS duration_ms INTEGER;

CREATE INDEX IF NOT EXISTS idx_rd_embedding
    ON routing_decisions
    USING hnsw (task_embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_rd_account_agent
    ON routing_decisions (account_id, selected_agent, created_at DESC);


CREATE TABLE IF NOT EXISTS account_beta_params (
    account_id      UUID NOT NULL,
    feature_hash    VARCHAR(64) NOT NULL,
    agent_name      VARCHAR(128) NOT NULL,
    alpha           INTEGER DEFAULT 1,
    beta            INTEGER DEFAULT 1,
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (account_id, feature_hash, agent_name)
);
