-- 初始化数据库扩展和角色
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 验证扩展已启用
SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'pg_trgm');
