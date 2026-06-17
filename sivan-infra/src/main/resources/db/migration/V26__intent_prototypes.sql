-- 意图原型表 — 用户可自定义闲聊/任务的原型文本，用于 IntentClassifier 语义分类

CREATE TABLE IF NOT EXISTS "intent_prototypes" (
    "prototype_key" VARCHAR(32) NOT NULL PRIMARY KEY,
    "prototype_text" TEXT NOT NULL,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

-- 默认闲聊原型
INSERT INTO "intent_prototypes" ("prototype_key", "prototype_text")
VALUES ('chat', '简单的问候聊天、日常对话、打招呼、表达感受、闲聊日常话题，不需要任何工具或操作，不需要处理文件或执行代码')
ON CONFLICT ("prototype_key") DO NOTHING;

-- 默认任务原型
INSERT INTO "intent_prototypes" ("prototype_key", "prototype_text")
VALUES ('task', '需要执行具体任务、操作文件、分析代码、运行计算、生成内容、处理数据、搜索查找、系统操作')
ON CONFLICT ("prototype_key") DO NOTHING;
