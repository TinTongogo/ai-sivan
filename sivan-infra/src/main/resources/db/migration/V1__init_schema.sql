-- ============================================
-- Sivan 灵枢 v1.0 初始 Schema
-- 基于 public.sql（数据库实际状态）生成
-- ============================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- Level 0: 无外键依赖的表
-- ============================================

CREATE TABLE IF NOT EXISTS "accounts" (
  "account_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "username" varchar(64) COLLATE "pg_catalog"."default" NOT NULL UNIQUE,
  "email" varchar(128) COLLATE "pg_catalog"."default" UNIQUE,
  "password_hash" varchar(256) COLLATE "pg_catalog"."default" NOT NULL,
  "display_name" varchar(128) COLLATE "pg_catalog"."default",
  "quota" jsonb DEFAULT '{}'::jsonb,
  "status" varchar(16) COLLATE "pg_catalog"."default" DEFAULT 'active'::character varying,
  "created_at" timestamptz(6) DEFAULT now(),
  "updated_at" timestamptz(6) DEFAULT now(),
  "preferences" jsonb,
  "short_id" varchar(32) COLLATE "pg_catalog"."default",
  "token_version" int4 NOT NULL DEFAULT 0
)
;

CREATE UNIQUE INDEX IF NOT EXISTS "uk_accounts_short_id" ON "accounts" USING btree (
  "short_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
) WHERE short_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS "flyway_schema_history" (
  "installed_rank" int4 NOT NULL,
  "version" varchar(50) COLLATE "pg_catalog"."default",
  "description" varchar(200) COLLATE "pg_catalog"."default" NOT NULL,
  "type" varchar(20) COLLATE "pg_catalog"."default" NOT NULL,
  "script" varchar(1000) COLLATE "pg_catalog"."default" NOT NULL,
  "checksum" int4,
  "installed_by" varchar(100) COLLATE "pg_catalog"."default" NOT NULL,
  "installed_on" timestamp(6) NOT NULL DEFAULT now(),
  "execution_time" int4 NOT NULL,
  "success" bool NOT NULL
)
;

CREATE TABLE IF NOT EXISTS "execution_artifacts" (
  "artifact_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "execution_id" uuid NOT NULL,
  "phase_index" int4 NOT NULL,
  "artifact_type" varchar(16) COLLATE "pg_catalog"."default" NOT NULL,
  "name" varchar(255) COLLATE "pg_catalog"."default" NOT NULL,
  "description" text COLLATE "pg_catalog"."default",
  "content" text COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now()
)
;

CREATE INDEX IF NOT EXISTS "idx_artifact_execution" ON "execution_artifacts" USING btree (
  "execution_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "exploration_state" (
  "account_id" uuid NOT NULL PRIMARY KEY,
  "call_count" int4 NOT NULL DEFAULT 0,
  "last_exploration_call" int4 NOT NULL DEFAULT '-3'::integer,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now()
)
;

CREATE TABLE IF NOT EXISTS "files" (
  "file_id" uuid NOT NULL PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "file_name" varchar(255) COLLATE "pg_catalog"."default" NOT NULL,
  "mime_type" varchar(127) COLLATE "pg_catalog"."default" NOT NULL,
  "file_size" int8 NOT NULL,
  "storage_path" varchar(512) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamptz(6) NOT NULL DEFAULT now()
)
;

CREATE INDEX IF NOT EXISTS "idx_files_account" ON "files" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "shared_templates" (
  "template_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "pattern_id" uuid NOT NULL,
  "owner_account_id" uuid NOT NULL,
  "visibility" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'PUBLIC'::character varying,
  "project_id" uuid,
  "allowed_accounts" text COLLATE "pg_catalog"."default",
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'ACTIVE'::character varying,
  "use_count" int4 NOT NULL DEFAULT 0,
  "success_count" int4 NOT NULL DEFAULT 0,
  "shared_at" timestamptz(6),
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "quality" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'NORMAL'::character varying
)
;

CREATE INDEX IF NOT EXISTS "idx_shared_owner" ON "shared_templates" USING btree (
  "owner_account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_shared_pattern" ON "shared_templates" USING btree (
  "pattern_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_shared_visibility" ON "shared_templates" USING btree (
  "visibility" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "strategy_performance" (
  "id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "strategy" varchar(32) COLLATE "pg_catalog"."default" NOT NULL,
  "decision_count" int4 NOT NULL DEFAULT 0,
  "success_count" int4 NOT NULL DEFAULT 0,
  "total_weight" float8 NOT NULL DEFAULT 1.0,
  "avg_confidence" float8 DEFAULT 0,
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "account_id" uuid,
  "total" int8 DEFAULT 0,
  "success" int8 DEFAULT 0,
  "sum_confidence" float8 DEFAULT 0,
  "created_at" timestamptz(6) DEFAULT now(),
  CONSTRAINT "uq_strategy_perf_account_strategy" UNIQUE ("account_id", "strategy")
)
;

CREATE TABLE IF NOT EXISTS "tool_match_logs" (
  "id" uuid NOT NULL PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "conversation_id" uuid NOT NULL,
  "tool_name" varchar(256) COLLATE "pg_catalog"."default" NOT NULL,
  "server_id" varchar(128) COLLATE "pg_catalog"."default",
  "similarity" float8,
  "threshold" float8,
  "passed" bool NOT NULL DEFAULT false,
  "created_at" timestamptz(6) NOT NULL
)
;

CREATE INDEX IF NOT EXISTS "idx_tool_match_logs_account" ON "tool_match_logs" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamptz_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_tool_match_logs_conversation" ON "tool_match_logs" USING btree (
  "conversation_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

-- ============================================
-- Level 1: 仅依赖 accounts
-- ============================================

CREATE TABLE IF NOT EXISTS "projects" (
  "project_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "description" text COLLATE "pg_catalog"."default",
  "sort_order" int4 DEFAULT 0,
  "created_at" timestamptz(6) DEFAULT now(),
  "updated_at" timestamptz(6) DEFAULT now(),
  "local_path" varchar(1024) COLLATE "pg_catalog"."default",
  "undeletable" bool NOT NULL DEFAULT false,
  "archived" bool NOT NULL DEFAULT false,
  "archived_at" timestamptz(6),
  "local_path_auto" bool NOT NULL DEFAULT false,
  "short_id" varchar(32) COLLATE "pg_catalog"."default",
  CONSTRAINT "projects_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE UNIQUE INDEX IF NOT EXISTS "uk_projects_short_id" ON "projects" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "short_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
) WHERE short_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS "contracts" (
  "contract_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "execution_id" uuid NOT NULL,
  "account_id" uuid NOT NULL,
  "phase" int4 NOT NULL,
  "source_agent" varchar(64) COLLATE "pg_catalog"."default",
  "target_agent" varchar(64) COLLATE "pg_catalog"."default",
  "content" text COLLATE "pg_catalog"."default",
  "content_type" varchar(32) COLLATE "pg_catalog"."default" DEFAULT 'text'::character varying,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "project_id" uuid,
  CONSTRAINT "contracts_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_contract_execution" ON "contracts" USING btree (
  "execution_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_contract_project" ON "contracts" USING btree (
  "project_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "instinct_patterns" (
  "pattern_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "task_signature" text COLLATE "pg_catalog"."default",
  "topology_json" jsonb,
  "success_count" int4 NOT NULL DEFAULT 0,
  "total_count" int4 NOT NULL DEFAULT 0,
  "active" bool NOT NULL DEFAULT false,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "feature_vector" jsonb,
  "execution_mode" varchar(32) COLLATE "pg_catalog"."default" DEFAULT 'SQUAD'::character varying,
  "version" int4 DEFAULT 1,
  "source_pattern_id" uuid,
  "hit_count" int4 DEFAULT 0,
  "mode_recommendation" jsonb,
  "mode_success_rate" float8,
  "last_match_at" timestamptz(6),
  CONSTRAINT "instinct_patterns_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "instinct_patterns_source_pattern_id_fkey" FOREIGN KEY ("source_pattern_id") REFERENCES "instinct_patterns" ("pattern_id") ON DELETE NO ACTION ON UPDATE NO ACTION
)
;

CREATE TABLE IF NOT EXISTS "llm_providers" (
  "provider_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid,
  "name" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "provider_type" varchar(32) COLLATE "pg_catalog"."default" NOT NULL,
  "api_key" varchar(512) COLLATE "pg_catalog"."default",
  "base_url" varchar(256) COLLATE "pg_catalog"."default",
  "models" text COLLATE "pg_catalog"."default",
  "active" bool NOT NULL DEFAULT true,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "is_default" bool NOT NULL DEFAULT false,
  "capabilities" text COLLATE "pg_catalog"."default" DEFAULT ''::text,
  "context_length" int4 NOT NULL DEFAULT 4096,
  "temperature" float8,
  "tags" varchar(256) COLLATE "pg_catalog"."default" DEFAULT ''::character varying,
  CONSTRAINT "llm_providers_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_llm_providers_account" ON "llm_providers" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_llm_providers_account_active" ON "llm_providers" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "active" "pg_catalog"."bool_ops" ASC NULLS LAST
);
CREATE UNIQUE INDEX IF NOT EXISTS "idx_llm_providers_default_per_account" ON "llm_providers" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
) WHERE is_default = true;

CREATE TABLE IF NOT EXISTS "mcp_server_configs" (
  "server_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "name" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "server_url" varchar(512) COLLATE "pg_catalog"."default" NOT NULL,
  "api_key" varchar(512) COLLATE "pg_catalog"."default",
  "transport" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'sse'::character varying,
  "active" bool NOT NULL DEFAULT true,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "mcp_server_configs_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_mcp_server_configs_account" ON "mcp_server_configs" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "pattern_feedback" (
  "feedback_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "pattern_id" uuid,
  "account_id" uuid NOT NULL,
  "execution_id" uuid NOT NULL,
  "actual_features" jsonb,
  "task_description" text COLLATE "pg_catalog"."default",
  "outcome" varchar(16) COLLATE "pg_catalog"."default" NOT NULL,
  "outcome_reason" text COLLATE "pg_catalog"."default",
  "token_cost" int4 NOT NULL DEFAULT 0,
  "deviation_json" jsonb,
  "source" varchar(32) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "pattern_feedback_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_feedback_account" ON "pattern_feedback" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_feedback_execution" ON "pattern_feedback" USING btree (
  "execution_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_feedback_pattern" ON "pattern_feedback" USING btree (
  "pattern_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "profile_changelog" (
  "log_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "source" varchar(32) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'manual'::character varying,
  "field_name" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "old_value" varchar(500) COLLATE "pg_catalog"."default",
  "new_value" varchar(500) COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "profile_changelog_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_profile_changelog_account" ON "profile_changelog" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamptz_ops" DESC NULLS FIRST
);

CREATE TABLE IF NOT EXISTS "routing_decisions" (
  "decision_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "task_description" text COLLATE "pg_catalog"."default",
  "selected_agent" varchar(64) COLLATE "pg_catalog"."default",
  "strategy" varchar(32) COLLATE "pg_catalog"."default",
  "success" bool NOT NULL,
  "confidence" float8,
  "context_json" jsonb,
  "reasoning" text COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "error_hint" text COLLATE "pg_catalog"."default",
  "conversation_id" uuid,
  "project_id" uuid,
  CONSTRAINT "routing_decisions_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_routing_decisions_conversation" ON "routing_decisions" USING btree (
  "conversation_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_routing_decisions_project" ON "routing_decisions" USING btree (
  "project_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "token_usage" (
  "token_usage_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "agent_id" uuid,
  "model_name" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "input_tokens" int4 NOT NULL DEFAULT 0,
  "output_tokens" int4 NOT NULL DEFAULT 0,
  "conversation_id" uuid,
  "source" varchar(32) COLLATE "pg_catalog"."default" NOT NULL,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "project_id" uuid,
  CONSTRAINT "token_usage_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_token_account" ON "token_usage" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamptz_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_token_agent" ON "token_usage" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "agent_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_token_model" ON "token_usage" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "model_name" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_token_project" ON "token_usage" USING btree (
  "project_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "tool_usage" (
  "tool_usage_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "agent_name" varchar(64) COLLATE "pg_catalog"."default",
  "tool_name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "server_id" varchar(128) COLLATE "pg_catalog"."default" NOT NULL DEFAULT ''::character varying,
  "success" bool NOT NULL DEFAULT true,
  "duration_ms" int4 NOT NULL DEFAULT 0,
  "conversation_id" uuid,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "tool_usage_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_tool_usage_account" ON "tool_usage" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "tool_name" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_tool_usage_agent" ON "tool_usage" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "agent_name" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST,
  "tool_name" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_tool_usage_created" ON "tool_usage" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "created_at" "pg_catalog"."timestamptz_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "user_profiles" (
  "profile_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "name" varchar(255) COLLATE "pg_catalog"."default",
  "bio" text COLLATE "pg_catalog"."default",
  "ai_language" varchar(10) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'auto'::character varying,
  "expertise" jsonb NOT NULL DEFAULT '[]'::jsonb,
  "active" bool NOT NULL DEFAULT true,
  "vector" vector(1024),
  "auto_learn" bool NOT NULL DEFAULT true,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "user_profiles_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_user_profiles_account" ON "user_profiles" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

-- ============================================
-- Level 2: 依赖 accounts + projects / mcp_server_configs
-- ============================================

CREATE TABLE IF NOT EXISTS "agents" (
  "agent_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "agent_name" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "display_name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "description" text COLLATE "pg_catalog"."default",
  "category" varchar(32) COLLATE "pg_catalog"."default",
  "system_prompt" text COLLATE "pg_catalog"."default" NOT NULL,
  "craft_declaration" text COLLATE "pg_catalog"."default",
  "skill_ids" text COLLATE "pg_catalog"."default" NOT NULL DEFAULT ''::text,
  "agent_type" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'USER'::character varying,
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'ACTIVE'::character varying,
  "version" int4 NOT NULL DEFAULT 1,
  "created_by" varchar(64) COLLATE "pg_catalog"."default",
  "usage_count" int4 NOT NULL DEFAULT 0,
  "last_used_at" timestamptz(6),
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "tool_requirements" jsonb,
  "optlock" int4 NOT NULL DEFAULT 0,
  CONSTRAINT "agents_account_id_agent_name_key" UNIQUE ("account_id", "agent_name"),
  CONSTRAINT "agents_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "agents_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_agents_account" ON "agents" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "conversations" (
  "conversation_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "title" varchar(256) COLLATE "pg_catalog"."default" DEFAULT '新对话'::character varying,
  "message_count" int4 NOT NULL DEFAULT 0,
  "last_message_at" timestamptz(6),
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "knowledge_base_ids" text COLLATE "pg_catalog"."default" DEFAULT '[]'::text,
  "mcp_server_ids" text COLLATE "pg_catalog"."default" NOT NULL DEFAULT '[]'::text,
  "compressed_context" text COLLATE "pg_catalog"."default",
  "compressed_up_to_msg_id" uuid,
  "goal_id" uuid,
  CONSTRAINT "conversations_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "conversations_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_conversation_account" ON "conversations" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_conversations_project" ON "conversations" USING btree (
  "project_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "knowledge_bases" (
  "kb_name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "description" text COLLATE "pg_catalog"."default" DEFAULT ''::text,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "knowledge_bases_pkey" PRIMARY KEY ("kb_name", "account_id"),
  CONSTRAINT "knowledge_bases_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "knowledge_bases_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE TABLE IF NOT EXISTS "mcp_tools" (
  "tool_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "server_id" uuid NOT NULL,
  "name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "title" varchar(256) COLLATE "pg_catalog"."default",
  "description" text COLLATE "pg_catalog"."default",
  "input_schema" jsonb,
  "output_schema" jsonb,
  "annotations" jsonb,
  "meta" jsonb,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "mcp_tools_server_id_fkey" FOREIGN KEY ("server_id") REFERENCES "mcp_server_configs" ("server_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE UNIQUE INDEX IF NOT EXISTS "idx_mcp_tools_name_server" ON "mcp_tools" USING btree (
  "server_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "name" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_mcp_tools_server" ON "mcp_tools" USING btree (
  "server_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "memory_entries" (
  "memory_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "level" varchar(16) COLLATE "pg_catalog"."default" NOT NULL,
  "scope_id" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "content" text COLLATE "pg_catalog"."default" NOT NULL,
  "metadata" jsonb DEFAULT '{}'::jsonb,
  "retention" numeric(5,4) NOT NULL DEFAULT 1.0,
  "access_count" int4 NOT NULL DEFAULT 0,
  "is_archived" bool NOT NULL DEFAULT false,
  "is_important" bool NOT NULL DEFAULT false,
  "summary" text COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "last_accessed_at" timestamptz(6) NOT NULL DEFAULT now(),
  "vector" vector(1024),
  CONSTRAINT "memory_entries_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "memory_entries_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

-- 注意: idx_memory_entries_cosine (hnsw vector_cosine_ops) 由应用层在运行时创建
CREATE INDEX IF NOT EXISTS "idx_memory_entries_scope" ON "memory_entries" USING btree (
  "scope_id" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_memory_level" ON "memory_entries" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "level" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_memory_retention" ON "memory_entries" USING btree (
  "retention" "pg_catalog"."numeric_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "messages" (
  "message_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "conversation_id" uuid NOT NULL,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "role" varchar(16) COLLATE "pg_catalog"."default",
  "content" text COLLATE "pg_catalog"."default" NOT NULL,
  "content_type" varchar(32) COLLATE "pg_catalog"."default" DEFAULT 'text'::character varying,
  "target_agent" varchar(64) COLLATE "pg_catalog"."default",
  "reply_to_id" uuid,
  "sort_order" int4,
  "status" varchar(16) COLLATE "pg_catalog"."default" DEFAULT 'COMPLETED'::character varying,
  "rating" varchar(16) COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "thinking" text COLLATE "pg_catalog"."default",
  "model" varchar(100) COLLATE "pg_catalog"."default",
  "total_tokens" int4,
  "duration_ms" int4,
  "thinking_duration_ms" int4,
  "chain" varchar(64) COLLATE "pg_catalog"."default",
  "images" text COLLATE "pg_catalog"."default",
  "generation_index" int4 NOT NULL DEFAULT 1,
  "generation_group" uuid,
  "attachments" text COLLATE "pg_catalog"."default",
  "thinking_tokens" int4,
  CONSTRAINT "uq_message_conversation_sort" UNIQUE ("conversation_id", "sort_order"),
  CONSTRAINT "messages_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "messages_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_message_conversation" ON "messages" USING btree (
  "conversation_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_messages_conversation_sort" ON "messages" USING btree (
  "conversation_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "sort_order" "pg_catalog"."int4_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "skills" (
  "skill_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "skill_code" varchar(64) COLLATE "pg_catalog"."default" NOT NULL,
  "name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "display_name" varchar(128) COLLATE "pg_catalog"."default",
  "description" text COLLATE "pg_catalog"."default",
  "content" text COLLATE "pg_catalog"."default",
  "category" varchar(64) COLLATE "pg_catalog"."default",
  "tags" text COLLATE "pg_catalog"."default" NOT NULL DEFAULT ''::text,
  "usage_count" int4 NOT NULL DEFAULT 0,
  "last_used_at" timestamptz(6),
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'ACTIVE'::character varying,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "skill_type" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'USER'::character varying,
  CONSTRAINT "skills_account_id_skill_code_key" UNIQUE ("account_id", "skill_code"),
  CONSTRAINT "skills_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "skills_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE TABLE IF NOT EXISTS "squad_executions" (
  "execution_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "squad_id" uuid,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "task_description" text COLLATE "pg_catalog"."default",
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'PENDING'::character varying,
  "topology_snapshot" jsonb,
  "context_json" jsonb,
  "current_phase" int4,
  "agent_state" jsonb,
  "error_message" text COLLATE "pg_catalog"."default",
  "started_at" timestamptz(6),
  "paused_at" timestamptz(6),
  "completed_at" timestamptz(6),
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "content" text COLLATE "pg_catalog"."default",
  "thinking" text COLLATE "pg_catalog"."default",
  CONSTRAINT "squad_executions_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "squad_executions_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_execution_status" ON "squad_executions" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_squad_executions_project" ON "squad_executions" USING btree (
  "project_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "squads" (
  "squad_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "description" text COLLATE "pg_catalog"."default",
  "mode" varchar(16) COLLATE "pg_catalog"."default",
  "topology_json" jsonb,
  "usage_count" int4 NOT NULL DEFAULT 0,
  "success_rate" float8,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "source" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'USER'::character varying,
  "active" bool NOT NULL DEFAULT true,
  "source_pattern_id" uuid,
  CONSTRAINT "squads_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "squads_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

-- ============================================
-- Level 3: 依赖 accounts + conversations + projects + squad_executions + routing_decisions
-- ============================================

CREATE TABLE IF NOT EXISTS "goals" (
  "goal_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "account_id" uuid NOT NULL,
  "project_id" uuid,
  "conversation_id" uuid,
  "title" varchar(256) COLLATE "pg_catalog"."default" NOT NULL,
  "description" text COLLATE "pg_catalog"."default" NOT NULL,
  "success_criteria" text COLLATE "pg_catalog"."default",
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'PENDING'::character varying,
  "auto_mode" varchar(24) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'AUTO'::character varying,
  "current_milestone" int4 NOT NULL DEFAULT 0,
  "total_tasks" int4 NOT NULL DEFAULT 0,
  "completed_tasks" int4 NOT NULL DEFAULT 0,
  "pause_reason" text COLLATE "pg_catalog"."default",
  "completed_at" timestamptz(6),
  "paused_at" timestamptz(6),
  "file_root_path" varchar(1024) COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "version" int8 NOT NULL DEFAULT 0,
  "source_squad_id" uuid,
  "source_execution_id" uuid,
  "squad_topology_json" text COLLATE "pg_catalog"."default",
  "current_phase_index" int4 NOT NULL DEFAULT 0,
  CONSTRAINT "goals_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "goals_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations" ("conversation_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "goals_project_id_fkey" FOREIGN KEY ("project_id") REFERENCES "projects" ("project_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_goals_account" ON "goals" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_goals_conversation" ON "goals" USING btree (
  "conversation_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_goals_project" ON "goals" USING btree (
  "project_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_goals_status" ON "goals" USING btree (
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "hitl_reviews" (
  "review_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "execution_id" uuid NOT NULL,
  "account_id" uuid NOT NULL,
  "phase" int4 NOT NULL,
  "phase_name" varchar(128) COLLATE "pg_catalog"."default",
  "input_content" text COLLATE "pg_catalog"."default",
  "output_content" text COLLATE "pg_catalog"."default",
  "human_feedback" text COLLATE "pg_catalog"."default",
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'PENDING'::character varying,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "expires_at" timestamptz(6),
  "corrected_content" text COLLATE "pg_catalog"."default",
  "restart_hint" text COLLATE "pg_catalog"."default",
  "restart_agent" varchar(128) COLLATE "pg_catalog"."default",
  CONSTRAINT "hitl_reviews_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "hitl_reviews_execution_id_fkey" FOREIGN KEY ("execution_id") REFERENCES "squad_executions" ("execution_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_hitl_execution" ON "hitl_reviews" USING btree (
  "execution_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_hitl_pending" ON "hitl_reviews" USING btree (
  "account_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "status" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "pipeline_steps" (
  "step_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "message_id" uuid NOT NULL,
  "routing_decision_id" uuid,
  "execution_id" uuid,
  "step_type" varchar(32) COLLATE "pg_catalog"."default" NOT NULL,
  "step_name" varchar(128) COLLATE "pg_catalog"."default",
  "status" varchar(16) COLLATE "pg_catalog"."default" NOT NULL,
  "sequence" int4 NOT NULL,
  "parent_step_id" uuid,
  "started_at" timestamptz(6),
  "completed_at" timestamptz(6),
  "duration_ms" int4,
  "input_summary" text COLLATE "pg_catalog"."default",
  "output_summary" text COLLATE "pg_catalog"."default",
  "agent_name" varchar(64) COLLATE "pg_catalog"."default",
  "model_name" varchar(64) COLLATE "pg_catalog"."default",
  "token_count" int4,
  "metadata_json" jsonb,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "pipeline_steps_execution_id_fkey" FOREIGN KEY ("execution_id") REFERENCES "squad_executions" ("execution_id") ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT "pipeline_steps_message_id_fkey" FOREIGN KEY ("message_id") REFERENCES "messages" ("message_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "pipeline_steps_parent_step_id_fkey" FOREIGN KEY ("parent_step_id") REFERENCES "pipeline_steps" ("step_id") ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT "pipeline_steps_routing_decision_id_fkey" FOREIGN KEY ("routing_decision_id") REFERENCES "routing_decisions" ("decision_id") ON DELETE SET NULL ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_pipeline_steps_execution" ON "pipeline_steps" USING btree (
  "execution_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_pipeline_steps_message" ON "pipeline_steps" USING btree (
  "message_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "sequence" "pg_catalog"."int4_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_pipeline_steps_parent" ON "pipeline_steps" USING btree (
  "parent_step_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

-- ============================================
-- Level 4: 依赖 goals
-- ============================================

CREATE TABLE IF NOT EXISTS "goal_milestones" (
  "milestone_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "goal_id" uuid NOT NULL,
  "name" varchar(256) COLLATE "pg_catalog"."default" NOT NULL,
  "description" text COLLATE "pg_catalog"."default",
  "sort_order" int4 NOT NULL DEFAULT 0,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "phase_index" int4 NOT NULL DEFAULT 0,
  "phase_mode" varchar(32) COLLATE "pg_catalog"."default",
  CONSTRAINT "goal_milestones_goal_id_fkey" FOREIGN KEY ("goal_id") REFERENCES "goals" ("goal_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_goal_milestones_goal" ON "goal_milestones" USING btree (
  "goal_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "sort_order" "pg_catalog"."int4_ops" ASC NULLS LAST
);

CREATE TABLE IF NOT EXISTS "goal_artifacts" (
  "artifact_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "goal_id" uuid NOT NULL,
  "milestone_order" int4 NOT NULL,
  "task_order" int4 NOT NULL,
  "file_path" varchar(1024) COLLATE "pg_catalog"."default" NOT NULL,
  "file_type" varchar(32) COLLATE "pg_catalog"."default",
  "summary" text COLLATE "pg_catalog"."default",
  "file_size" int8 DEFAULT 0,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "goal_artifacts_goal_id_fkey" FOREIGN KEY ("goal_id") REFERENCES "goals" ("goal_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_goal_artifacts_goal" ON "goal_artifacts" USING btree (
  "goal_id" "pg_catalog"."uuid_ops" ASC NULLS LAST
);

-- ============================================
-- Level 5: 依赖 goals + goal_milestones
-- ============================================

CREATE TABLE IF NOT EXISTS "goal_tasks" (
  "task_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "goal_id" uuid NOT NULL,
  "milestone_id" uuid,
  "sort_order" int4 NOT NULL DEFAULT 0,
  "description" text COLLATE "pg_catalog"."default" NOT NULL,
  "completed" bool NOT NULL DEFAULT false,
  "status" varchar(16) COLLATE "pg_catalog"."default",
  "artifact_summary" text COLLATE "pg_catalog"."default",
  "input_artifact" text COLLATE "pg_catalog"."default",
  "output_files" jsonb DEFAULT '[]'::jsonb,
  "task_ref" varchar(64) COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
  "agent_index" int4 NOT NULL DEFAULT 0,
  "agent_name" varchar(128) COLLATE "pg_catalog"."default",
  CONSTRAINT "goal_tasks_goal_id_fkey" FOREIGN KEY ("goal_id") REFERENCES "goals" ("goal_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "goal_tasks_milestone_id_fkey" FOREIGN KEY ("milestone_id") REFERENCES "goal_milestones" ("milestone_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_goal_tasks_goal" ON "goal_tasks" USING btree (
  "goal_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "sort_order" "pg_catalog"."int4_ops" ASC NULLS LAST
);
CREATE INDEX IF NOT EXISTS "idx_goal_tasks_milestone" ON "goal_tasks" USING btree (
  "milestone_id" "pg_catalog"."uuid_ops" ASC NULLS LAST,
  "sort_order" "pg_catalog"."int4_ops" ASC NULLS LAST
);

-- ============================================
-- Level 6: 依赖 accounts + knowledge_bases
-- ============================================

CREATE TABLE IF NOT EXISTS "kb_documents" (
  "doc_id" uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  "kb_name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "account_id" uuid NOT NULL,
  "filename" varchar(256) COLLATE "pg_catalog"."default" NOT NULL,
  "source_path" varchar(512) COLLATE "pg_catalog"."default",
  "file_type" varchar(16) COLLATE "pg_catalog"."default" NOT NULL,
  "char_count" int4 NOT NULL DEFAULT 0,
  "chunk_count" int4 NOT NULL DEFAULT 0,
  "text_content" text COLLATE "pg_catalog"."default",
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT "kb_documents_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "kb_documents_kb_name_account_id_fkey" FOREIGN KEY ("kb_name", "account_id") REFERENCES "knowledge_bases" ("kb_name", "account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

-- ============================================
-- Level 7: 依赖 kb_documents + knowledge_bases
-- ============================================

CREATE SEQUENCE IF NOT EXISTS kb_vectors_id_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

CREATE TABLE IF NOT EXISTS "kb_vectors" (
  "id" int8 NOT NULL DEFAULT nextval('kb_vectors_id_seq'::regclass) PRIMARY KEY,
  "kb_name" varchar(128) COLLATE "pg_catalog"."default" NOT NULL,
  "account_id" uuid NOT NULL,
  "chunk_id" uuid NOT NULL,
  "doc_id" uuid NOT NULL,
  "text_content" text COLLATE "pg_catalog"."default" NOT NULL,
  "content_type" varchar(20) COLLATE "pg_catalog"."default" NOT NULL DEFAULT 'text'::character varying,
  "image_path" varchar(512) COLLATE "pg_catalog"."default",
  "vector" vector(1024) NOT NULL,
  "metadata" jsonb DEFAULT '{}'::jsonb,
  "is_deleted" bool NOT NULL DEFAULT false,
  "created_at" timestamptz(6) NOT NULL DEFAULT now(),
  "content_hash" varchar(32) COLLATE "pg_catalog"."default",
  CONSTRAINT "kb_vectors_doc_id_fkey" FOREIGN KEY ("doc_id") REFERENCES "kb_documents" ("doc_id") ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT "kb_vectors_kb_name_account_id_fkey" FOREIGN KEY ("kb_name", "account_id") REFERENCES "knowledge_bases" ("kb_name", "account_id") ON DELETE CASCADE ON UPDATE NO ACTION
)
;

CREATE INDEX IF NOT EXISTS "idx_kb_vectors_content_hash" ON "kb_vectors" USING btree (
  "content_hash" COLLATE "pg_catalog"."default" "pg_catalog"."text_ops" ASC NULLS LAST
);
-- 注意: idx_kb_vectors_cosine (hnsw vector_cosine_ops) 由应用层在运行时创建
CREATE INDEX IF NOT EXISTS "idx_kb_vectors_text_content_gin" ON "kb_vectors" USING gin (
  "text_content" COLLATE "pg_catalog"."default" "gin_trgm_ops"
);

-- ============================================
-- 数据修复
-- ============================================

UPDATE squads SET active = TRUE WHERE active IS NULL;
UPDATE llm_providers SET is_default = FALSE WHERE is_default IS NULL;
