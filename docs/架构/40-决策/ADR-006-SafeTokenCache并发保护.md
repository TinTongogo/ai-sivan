# ADR-006：SafeTokenCache 并发保护

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：第三方终审 → 系统架构组

---

## 背景

第三方终审发现 **C4 关注点**：`estimateSubtreeTokens()` 的增量缓存 `cachedSubtreeTokens` 在多线程下存在竞争。PARALLEL 模式下多个叶子同时完成，`onStatusChanged()` 从叶子往根遍历更新祖先链，多个线程可能同时更新同一祖先节点的缓存。

初审（架构评审 R02）独立发现了同一问题。

## 决策

将 `cachedSubtreeTokens` 替换为 `SafeTokenCache`，使用 `AtomicLong` + `synchronized` 保护祖先链更新：

```java
class SafeTokenCache {
    private final AtomicLong cachedTokens = new AtomicLong(-1);

    long get() { return cachedTokens.get(); }
    void set(long v) { cachedTokens.set(v); }
    boolean isStale() { return cachedTokens.get() < 0; }
    void invalidate() { cachedTokens.set(-1); }

    synchronized void updateAncestors(TreeNode node) {
        invalidate();
        TreeNode p = node.parent();
        while (p != null) {
            if (p instanceof CompressibleNode cn) {
                ((SafeTokenCache) cn.tokenCache()).invalidate();
            }
            p = p.parent();
        }
    }
}
```

## 理由

1. **`AtomicLong` 保证单节点读写的原子性**：`get()` / `set()` / `compareAndSet()` 均为 CAS 操作，无锁。
2. **`synchronized` 保护祖先链遍历**：`updateAncestors()` 加方法级锁，确保同一时刻只有一个线程在更新祖先链缓存。由于 PARALLEL 模式下叶子节点通常分布在树的不同分支，竞争概率低，锁的开销可忽略。
3. **存量 vs 增量分离**：`set()` 用于初始加载（O(1)），`updateAncestors()` 用于叶子完成时增量更新（O(log n)），两种场景互不干扰。

## 参考文献

- [review-第三方终审报告.md](../20-评审/review-第三方终审报告.md) §2.3 C4
- [review-架构评审报告.md](../20-评审/review-架构评审报告.md) R02
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §SafeTokenCache
