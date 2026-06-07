-- 测试中忽略外键约束，配合 @Transactional 确保同连接跨语句生效
SET session_replication_role = 'replica';
