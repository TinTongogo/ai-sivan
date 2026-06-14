# ADR-005：ProgressHeartbeat 进度心跳节流

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：第三方终审 → 系统架构组

---

## 背景

第三方终审发现 **C3 关注点**：PARALLEL 模式下多个 TaskNode 同时完成，每次 completion 都触发 `ProgressAggregator.aggregate()` 遍历整棵树，导致频繁的读写竞争。

此外，初审（架构评审 R01）和交叉评审（周-R1）均独立发现了同一问题。

## 决策

进度重算使用「心跳节流」——节点更新后不立即重算，而是标记脏标志，由一个定时线程每 5 秒统一重算一次。

```java
@Component
class ProgressHeartbeat {
    private volatile boolean dirty = false;

    @Scheduled(fixedRate = 5000)
    void beat(ProgressAggregator aggregator, ForestNode root) {
        if (!dirty) return;
        Progress p = aggregator.aggregate(root);
        dirty = false;
        emitProgress(p);
    }

    void markDirty() { this.dirty = true; }
}
```

## 理由

1. **消除读写竞争**：PARALLEL 模式下 N 个节点同时完成 → N 次 `markDirty()`（O(1)），而非 N 次全树遍历（O(n)）。
2. **定时串行化**：重算由 `@Scheduled` 线程串行执行，天然避免并发问题。
3. **延迟可接受**：5 秒心跳延迟对 SUMMARY 交付模式无感知；实时对话场景的进度条短暂停顿属可接受范围。
4. **设计简洁**：一个 volatile boolean + 一行 `@Scheduled`，无锁、无队列、无外部依赖。

## 参考文献

- [review-第三方终审报告.md](../20-评审/review-第三方终审报告.md) §2.3 C3
- [review-架构评审报告.md](../20-评审/review-架构评审报告.md) R01
- [review-最终交叉评审报告.md](../20-评审/review-最终交叉评审报告.md) 周-R1
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §9.1 ProgressHeartbeat
