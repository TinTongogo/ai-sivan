# ADR-043：MCP 服务器账号级连接隔离

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

`ToolProviderManager.connectMcpServer()` 仅依赖 `McpServerConfig`，未设计账号与 MCP 服务器之间的授权绑定。用户 A 配置的 GitHub MCP 可能被用户 B 复用。

## 决策

MCP 连接以 `accountId` 为维度隔离，维护 `Map<UUID, Map<String, ToolProvider>>` 二级映射：第一层 accountId，第二层 serverId。同一用户跨设备登录共享连接池。

## 理由

账号隔离是多租户安全的基础——用户 A 的 GitHub 凭证不能被用户 B 使用。

## 参考文献

- [07-工具动态感知.md](../10-设计/07-工具动态感知.md) §4.3
