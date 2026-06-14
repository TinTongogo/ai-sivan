# ADR-004：NodeContext 封装递归上下文

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：第三方终审 → 系统架构组

---

## 背景

第三方终审发现 **C2 关注点**：`depth` 参数在 `ExecutionContext` 移除后作为独立参数在 `Continuation.execute(child, ctx, depth + 1)` 中传递。如果未来有更多递归相关上下文（如"当前路径的 span ID"），这些参数会越加越多。

## 决策

将 `depth` 和 `span` 等递归上下文封装为一个 `NodeContext` record，由 `Continuation` 统一传递。

```java
record NodeContext(int depth, SpanContext span) {
    NodeContext next() { return new NodeContext(depth + 1, span.child()); }
    static NodeContext root(SpanContext span) { return new NodeContext(0, span); }
}
```

### 影响范围

| 接口/类 | 变更 |
|---------|------|
| `Continuation.execute()` | `int depth` → `NodeContext nodeCtx` |
| `ModeStrategy.execute()` | `int depth` → `NodeContext nodeCtx` |
| 全部 5 个 ModeStrategy 实现 | `depth + 1` → `nodeCtx.next()` |
| `ModeDispatcher.dispatch()` | `int depth` → `NodeContext nodeCtx` |

## 理由

1. **开闭原则**：未来需要增加递归上下文（如 parentSpan、traceTags）时，只需在 `NodeContext` record 中增加字段，`next()` 方法自动传播，所有调用链无需修改。
2. **类型安全**：`NodeContext` 是显式类型，比零散的 `int depth` + `SpanContext span` 参数更安全。
3. **与 Continuation 解耦一致**：Continuation 本就是为了避免 ModeStrategy 依赖 ForestExecutor，NodeContext 进一步避免递归参数随需求膨胀。

## 参考文献

- [review-第三方终审报告.md](../20-评审/review-第三方终审报告.md) §1.3 C2
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §Continuation + NodeContext
