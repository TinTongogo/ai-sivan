# ADR-010：AuditedSecretStore 密钥访问审计

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：第三方终审 → 系统架构组

---

## 背景

第三方终审发现 **C8 关注点**：`SecretStore` 接口设计是完备的，但没有设计"谁在什么时候访问了哪个密钥"的审计。在安全合规审计中，密钥访问日志是常见需求。

## 决策

为 `SecretStore` 增加 `AuditedSecretStore` 包装器（Decorator 模式），记录每次 `get()` 操作：

```java
class AuditedSecretStore implements SecretStore {
    private final SecretStore delegate;
    private final AuditManager audit;

    AuditedSecretStore(SecretStore delegate, AuditManager audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override
    public Optional<String> get(String key) {
        audit.recordKeyAccess(key);
        return delegate.get(key);
    }

    @Override
    public void set(String key, String value) {
        audit.recordKeyAccess(key);
        delegate.set(key, value);
    }

    @Override
    public void delete(String key) {
        audit.recordKeyAccess(key);
        delegate.delete(key);
    }
}
```

## 理由

1. **不侵入现有接口**：Decorator 模式包装 `SecretStore`，原始接口无需任何修改。
2. **统一审计入口**：与 C6（MCP 审计聚合）共用 `AuditManager.recordKeyAccess()`，所有审计走同一通道。
3. **可选启用**：`AuditedSecretStore` 在配置中决定是否启用，非强制。本地开发环境可跳过审计以降低噪音。
4. **完整覆盖**：`get()` / `set()` / `delete()` 三个操作均记录，不留盲区。

## 参考文献

- [review-第三方终审报告.md](../20-评审/review-第三方终审报告.md) §3.3 C8
- [05-沙箱与安全.md](../10-设计/05-沙箱与安全.md) §AuditedSecretStore
