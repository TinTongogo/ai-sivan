-- V23：修复 routing_decisions.task_embedding 向量维度（1024）
-- 当前实际维度与 bge-m3:latest 模型输出维度（1024）不匹配。
-- 注意：ALTER COLUMN TYPE 会隐式重建 HNSW 索引。

ALTER TABLE routing_decisions
    ALTER COLUMN task_embedding TYPE vector(1024)
    USING task_embedding::vector(1024);
