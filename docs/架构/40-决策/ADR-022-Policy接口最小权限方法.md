# ADR-022：Policy 接口增加最小权限方法

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：交叉评审 → 系统架构组

---

## 背景

交叉评审发现 **吴-R1 关注点**：`FilePolicy` 没有区分"读取"和"写入"的权限范围。策略粒度是"文件操作"而非"文件读/文件写"。当前 `FileWrite` 额外检查了 `output/` 和 `data/` 子目录，但这是路径级别而非用户权限级别的限制。

## 决策

在 `Policy<T>` 接口中增加 `requiredPermission()` 默认方法：

```java
interface Policy<T extends Action> {
    void validate(T action, SecurityContext ctx);
    Class<T> actionType();

    /** 执行本策略所需的最小权限。默认 "*" = 无限制。 */
    default String requiredPermission() { return "*"; }
}
```

各 Action 实现覆写所需权限：

| Action | requiredPermission |
|--------|-------------------|
| `FileRead` | `file:read` |
| `FileWrite` | `file:write` |
| `ShellExec` | `shell:exec` |
| `HttpRequest` | `http:request` |
| `McpToolCall` | `mcp:call` |

`SandboxManager` 在 `validate()` 前检查用户是否拥有该权限。

## 理由

1. 最小权限原则是实现阶段必须执行的安全基线——`file_list` 操作不应具有写入权限。
2. `default` 方法使现有策略无需立即适配，向后兼容。
3. 权限命名采用 `domain:action` 格式，与主流 RBAC 模型一致。

## 参考文献

- [review-最终交叉评审报告.md](../20-评审/review-最终交叉评审报告.md) 吴-R1
- [05-沙箱与安全.md](../10-设计/05-沙箱与安全.md) §4.1 Policy 接口
