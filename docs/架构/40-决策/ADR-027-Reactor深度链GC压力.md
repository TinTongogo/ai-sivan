# ADR-027：Reactor 深度链 GC 压力——Flux.defer 惰性构建

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：架构评审 → 系统架构组

---

## 背景

架构评审发现 **R04 关注点**：`SequentialModeStrategy` 使用 `Flux.concatWith` 链构建执行序列，深度 1000 的树会产生 1000 个 `concatWith` 调用的链。每个 `concatWith` 创建一个新的 Flux 对象，1000 节点 = 1000 个短生命周期 Flux 对象 → GC 压力。

## 决策

使用 `Flux.defer()` 包裹延迟执行，避免 eager 构建长链：

```java
// ✅ 惰性构建：concatWith 不触发执行，实际执行时按需 compute
chain = chain.concatWith(Flux.defer(() ->
    next.execute(child, ctx, nodeCtx.next())
));
```

### 补充措施

| 措施 | 触发条件 |
|------|---------|
| 使用 `Flux.defer()` 惰性构建 | 所有 ModeStrategy 实现 |
| 深度 > 50 时切换到 `boundedElastic` 调度 | Phase 0 压测验证 |
| 1000 节点 Reactor 链 GC 压测 | Phase 0 (P0-1) |

## 理由

1. `Flux.concatWith` 本身是惰性的——链构建只创建 Flux 对象，不触发执行。GC 压力来自 1000 个对象的分配。
2. `Flux.defer()` 将每次 `next.execute()` 的创建也惰性化，减少 eager 分配的对象数量。
3. 深度 1000 是极端情况，实际对话树的深度通常在 10 以内。
4. Phase 0 压测会验证 GC 是否成为瓶颈（1000 节点 Reactor 链 GC 观察）。

## 参考文献

- [review-架构评审报告.md](../20-评审/review-架构评审报告.md) R04
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §SequentialModeStrategy
- [arc-09-千节点压测分析.md](../30-计划与测试/arc-09-千节点压测分析.md) §2.5
