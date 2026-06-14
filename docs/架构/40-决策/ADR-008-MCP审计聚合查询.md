# ADR-008：MCP 审计聚合查询接口

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：第三方终审 → 系统架构组

---

## 背景

第三方终审发现 **C6 关注点**：当前 MCP 由用户自行按需连接和授权，只有单次审计日志。但在企业场景中，IT 管理员需要知道"哪些员工连接了哪些 MCP 服务器、用了多久、传输了多少数据"，缺少聚合视图。

交叉评审（吴-R2 审计日志保留策略）也涉及审计模块的增强。

## 决策

在 `AuditManager` 上增加聚合查询接口，支持按 serverId + accountId + 时间范围查询：

```java
interface AuditManager {
    // ...原有方法...
    
    /** MCP 聚合查询：按 serverId 过滤。 */
    List<McpAuditSummary> findByServer(String serverId, Instant from, Instant to);
    
    /** MCP 聚合查询：按 accountId 过滤。 */
    List<McpAuditSummary> findByAccount(UUID accountId, Instant from, Instant to);
}

record McpAuditSummary(UUID accountId, String serverId, String toolName,
                       int callCount, Instant firstCall, Instant lastCall) {}
```

### 非功能性约束

- 查询范围限制在 90 天内（配合 ADR 中审计日志的 90 天 TTL）
- 聚合查询走只读副本（如有），不影响主库写入性能
- 该接口数据为后续管理面板的前置条件，Phase 0/1 不实现 UI

## 理由

1. 企业部署的必经能力——管理面板需要聚合数据才能展示"谁连接了什么"。
2. 接口设计为只读查询，不涉及审计日志的写入路径改造，风险低。
3. 与 C8 （AuditedSecretStore）共用 `AuditManager`，统一审计入口。

## 参考文献

- [review-第三方终审报告.md](../20-评审/review-第三方终审报告.md) §3.3 C6
- [05-沙箱与安全.md](../10-设计/05-沙箱与安全.md) §4.5
