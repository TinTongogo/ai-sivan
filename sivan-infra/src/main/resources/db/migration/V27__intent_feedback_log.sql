-- 意图分类反馈日志 — 记录每次分类决策和用户纠正，用于持续学习

CREATE TABLE IF NOT EXISTS "intent_feedback_log" (
    "log_id"            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "account_id"        UUID        NOT NULL,
    "conversation_id"   UUID,
    "message_id"        UUID,
    "message_text"      TEXT        NOT NULL,
    "predicted_intent"  VARCHAR(16) NOT NULL,   -- chat / task
    "confidence"        DOUBLE PRECISION,        -- 任务相似度 - 闲聊相似度（正值=偏向task）
    "user_correction"   VARCHAR(16),             -- NULL / correct / incorrect
    "corrected_intent"  VARCHAR(16),             -- 用户纠正后的意图（chat / task）
    "created_at"        TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS "idx_ifl_account" ON "intent_feedback_log" ("account_id", "created_at" DESC);
CREATE INDEX IF NOT EXISTS "idx_ifl_message" ON "intent_feedback_log" ("message_id");
