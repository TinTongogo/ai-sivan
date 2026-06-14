-- V14：清理 projects.local_path 中的绝对路径，提取为相对路径。
-- 旧代码将 rootPath + acctShortId + projectShortId 拼成绝对路径存入 DB，
-- 如 /Users/yao/sivan/happy-moss/bold-wind → happy-moss/bold-wind。
-- 只处理 local_path_auto=true 的记录（自动创建，路径格式确定）。

UPDATE projects
SET local_path = regexp_replace(local_path, '^.*/([^/]+/[^/]+)$', '\1')
WHERE local_path_auto = TRUE
  AND local_path LIKE '/%';
