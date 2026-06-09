-- ============================================
-- V6: Forest 自主持续任务补充表
-- 支持 HITL 审批、A2A 通信、执行溯源、产物管理、自主调度
-- ============================================

-- ── 1. Forest HITL 审批记录 ──
CREATE TABLE IF NOT EXISTS "forest_hitl_reviews" (
    "review_id"   UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "forest_id"   UUID           NOT NULL,
    "node_id"     VARCHAR        NOT NULL,
    "account_id"  UUID           NOT NULL,
    "reason"      TEXT,
    "actions"     JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "status"      VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    "feedback"    TEXT,
    "decided_by"  UUID,
    "decided_at"  TIMESTAMPTZ(6),
    "expires_at"  TIMESTAMPTZ(6),
    "created_at"  TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "fk_fhr_forest"   FOREIGN KEY ("forest_id")  REFERENCES "forests"("forest_id")   ON DELETE CASCADE,
    CONSTRAINT "fk_fhr_account"  FOREIGN KEY ("account_id") REFERENCES "accounts"("account_id") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS "idx_fhr_forest"   ON "forest_hitl_reviews" ("forest_id");
CREATE INDEX IF NOT EXISTS "idx_fhr_pending"  ON "forest_hitl_reviews" ("account_id", "status");

-- ── 2. Forest 执行产物 ──
CREATE TABLE IF NOT EXISTS "forest_artifacts" (
    "artifact_id"   UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "forest_id"     UUID           NOT NULL,
    "node_id"       VARCHAR        NOT NULL,
    "artifact_type" VARCHAR(32)    NOT NULL,
    "name"          VARCHAR(256)   NOT NULL,
    "description"   TEXT,
    "content"       TEXT,
    "metadata"      JSONB          DEFAULT '{}'::jsonb,
    "created_at"    TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "fk_fa_forest" FOREIGN KEY ("forest_id") REFERENCES "forests"("forest_id") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS "idx_fa_forest" ON "forest_artifacts" ("forest_id");

-- ── 3. A2A Agent 通信记录 ──
CREATE TABLE IF NOT EXISTS "forest_agent_messages" (
    "message_id"     UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "forest_id"      UUID           NOT NULL,
    "scope_node_id"  VARCHAR        NOT NULL,
    "source_agent"   VARCHAR(128)   NOT NULL,
    "target_agent"   VARCHAR(128),
    "topic"          VARCHAR(256),
    "message_type"   VARCHAR(16)    NOT NULL DEFAULT 'REQUEST',
    "payload"        JSONB,
    "correlation_id" UUID,
    "created_at"     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "fk_fam_forest" FOREIGN KEY ("forest_id") REFERENCES "forests"("forest_id") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS "idx_fam_forest"       ON "forest_agent_messages" ("forest_id");
CREATE INDEX IF NOT EXISTS "idx_fam_scope"        ON "forest_agent_messages" ("forest_id", "scope_node_id");
CREATE INDEX IF NOT EXISTS "idx_fam_correlation"  ON "forest_agent_messages" ("correlation_id");
CREATE INDEX IF NOT EXISTS "idx_fam_topic"        ON "forest_agent_messages" ("topic");

-- ── 4. Forest 执行日志（事件溯源） ──
CREATE TABLE IF NOT EXISTS "forest_execution_logs" (
    "log_id"        UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "forest_id"     UUID           NOT NULL,
    "node_id"       VARCHAR        NOT NULL,
    "event_type"    VARCHAR(16)    NOT NULL,
    "status_before" VARCHAR(16),
    "status_after"  VARCHAR(16),
    "message"       TEXT,
    "metadata"      JSONB          DEFAULT '{}'::jsonb,
    "created_at"    TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "fk_fel_forest" FOREIGN KEY ("forest_id") REFERENCES "forests"("forest_id") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS "idx_fel_forest"   ON "forest_execution_logs" ("forest_id");
CREATE INDEX IF NOT EXISTS "idx_fel_node"     ON "forest_execution_logs" ("forest_id", "node_id");
CREATE INDEX IF NOT EXISTS "idx_fel_type"     ON "forest_execution_logs" ("forest_id", "event_type");
CREATE INDEX IF NOT EXISTS "idx_fel_created"  ON "forest_execution_logs" ("forest_id", "created_at");

-- ── 5. Forest 自主调度 ──
CREATE TABLE IF NOT EXISTS "forest_schedule" (
    "schedule_id"  UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "forest_id"    UUID           NOT NULL,
    "account_id"   UUID           NOT NULL,
    "title"        VARCHAR(256)   NOT NULL,
    "cron_expr"    VARCHAR(64),
    "status"       VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    "max_runs"     INT            NOT NULL DEFAULT 0,
    "run_count"    INT            NOT NULL DEFAULT 0,
    "last_run_at"  TIMESTAMPTZ(6),
    "last_result"  VARCHAR(16),
    "next_run_at"  TIMESTAMPTZ(6),
    "created_at"   TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    "updated_at"   TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    CONSTRAINT "fk_fs_forest"  FOREIGN KEY ("forest_id")  REFERENCES "forests"("forest_id")   ON DELETE CASCADE,
    CONSTRAINT "fk_fs_account" FOREIGN KEY ("account_id") REFERENCES "accounts"("account_id") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS "idx_fs_account" ON "forest_schedule" ("account_id", "status");
CREATE INDEX IF NOT EXISTS "idx_fs_next"    ON "forest_schedule" ("next_run_at") WHERE "status" = 'ACTIVE';
