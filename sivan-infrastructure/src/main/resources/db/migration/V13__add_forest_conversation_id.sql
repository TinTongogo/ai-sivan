-- ============================================
-- V13: forests 表添加 conversation_id
-- ============================================

ALTER TABLE "forests" ADD COLUMN IF NOT EXISTS "conversation_id" UUID;

CREATE INDEX IF NOT EXISTS "idx_forests_conversation" ON "forests" ("conversation_id");
