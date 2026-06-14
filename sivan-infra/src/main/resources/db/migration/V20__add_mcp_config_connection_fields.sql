-- 07-工具动态感知 §5.1: 增加 MCP 服务器配置的连接状态管理字段
ALTER TABLE mcp_server_configs
    ADD COLUMN IF NOT EXISTS connection_status VARCHAR(16) DEFAULT 'DISCONNECTED',
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(512),
    ADD COLUMN IF NOT EXISTS last_connected_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS tool_count INTEGER DEFAULT 0;
