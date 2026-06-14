-- ── forest_agent_messages.payload 改为 TEXT ──
-- payload 实际储存的是 A2A 消息纯文本，而非 JSON 结构化数据。
-- JSONB 类型强制要求合法 JSON，导致普通文本插入时报错（invalid input syntax for type json）。

ALTER TABLE "forest_agent_messages"
    ALTER COLUMN "payload" TYPE TEXT;
