# ADR-041：SecretStore 生产实现与 MCP 密钥存储

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

设计了 `SecretStore` 接口，但默认实现为 `EnvironmentSecretStore`（只读，不支持写），无密钥轮换或 Vault 集成。MCP 服务器配置中的 `api-key` 如何安全存储未详细设计。

## 决策

1. `McpServerConfig` 不直接存储密钥值，改为引用 `SecretStore` 中的 key 名：
   `record McpServerConfig(String serverId, String url, String credentialKey)`
2. 连接时通过 `SecretStore.get(config.credentialKey())` 读取凭证。
3. Phase 0 用环境变量实现（不支持轮换），Phase 2 引入 Vault/AWS SecretStore。

## 理由

密钥值不应明文存储在配置文件中。引用 key 名的设计使 SecretStore 实现可热切换。

## 参考文献

- [05-沙箱与安全.md](../10-设计/05-沙箱与安全.md) §5.1
