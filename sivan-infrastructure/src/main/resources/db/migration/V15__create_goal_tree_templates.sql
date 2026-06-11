-- V15：创建 GoalTreeTemplate 存储表
-- 模板树结构序列化为 JSONB，避免递归表关联

CREATE TABLE IF NOT EXISTS "goal_tree_templates" (
    "template_id"   uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    "account_id"    uuid NOT NULL,
    "name"          varchar(255) NOT NULL,
    "description"   text,
    "root_json"     jsonb NOT NULL,
    "usage_count"   int4 NOT NULL DEFAULT 0,
    "success_count" int4 NOT NULL DEFAULT 0,
    "created_at"    timestamptz NOT NULL DEFAULT now(),
    "updated_at"    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT "goal_tree_templates_account_id_fkey"
        FOREIGN KEY ("account_id") REFERENCES "accounts" ("account_id")
        ON DELETE CASCADE ON UPDATE NO ACTION
);

CREATE INDEX IF NOT EXISTS "idx_goal_tree_templates_account"
    ON "goal_tree_templates" USING btree ("account_id" ASC NULLS LAST);

CREATE INDEX IF NOT EXISTS "idx_goal_tree_templates_name"
    ON "goal_tree_templates" USING btree ("name" ASC NULLS LAST);
