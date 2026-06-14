-- ============================================
-- V9: 清理 V1 孤立表
-- 这些表由 V1 创建，无 JPA 实体映射，无代码引用。
-- Forest 架构已完全替代这些 V1 概念。
-- ============================================

DROP TABLE IF EXISTS contracts CASCADE;
DROP TABLE IF EXISTS execution_artifacts CASCADE;
DROP TABLE IF EXISTS squads CASCADE;
DROP TABLE IF EXISTS squad_executions CASCADE;
DROP TABLE IF EXISTS hitl_reviews CASCADE;
