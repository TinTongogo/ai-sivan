-- isDefault 拆分为三列，每 tag 组独立默认，DB 层保证唯一性
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS is_chat_default   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS is_embed_default  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS is_rerank_default BOOLEAN NOT NULL DEFAULT FALSE;

-- 迁移已有 is_default 数据到新列
UPDATE llm_providers SET is_chat_default   = TRUE WHERE is_default = TRUE AND tags LIKE '%chat%';
UPDATE llm_providers SET is_embed_default  = TRUE WHERE is_default = TRUE AND tags LIKE '%embedding%';
UPDATE llm_providers SET is_rerank_default = TRUE WHERE is_default = TRUE AND tags LIKE '%reranker%';

-- 唯一索引已由前序迁移删除，重建为三组独立索引
DROP INDEX IF EXISTS idx_llm_providers_default_per_account;
CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_providers_chat_default   ON llm_providers(account_id) WHERE is_chat_default = TRUE;
CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_providers_embed_default  ON llm_providers(account_id) WHERE is_embed_default = TRUE;
CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_providers_rerank_default ON llm_providers(account_id) WHERE is_rerank_default = TRUE;
