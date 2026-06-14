# ADR-025：Capability 类型发现改为 Optional 返回

> **状态**：⏸ 暂缓（实现阶段适配）
> **日期**：2026-06-06
> **作者**：交叉评审 → 系统架构组

---

## 背景

交叉评审发现 **郑-R1 关注点**：`model.as(XxxCapability.class)` 在 Capability 不存在时返回 null。调用方需要每次检查 null，漏检会导致 NPE。

当前缓解：`MultimodalRouter` 中 `findSingleByCapabilityType` 抛异常而非返回 null。

## 决策

将 `as()` 的返回类型改为 `Optional<T>`：

```java
// 当前
<C extends Capability> C as(Class<C> type);        // 可能返回 null

// 改为
<C extends Capability> Optional<C> as(Class<C> type); // 调用方强制处理 absent
```

此变更在 Phase 2（多模型 + 路由开发）时一并执行，不在 Phase 0 范围内。

## 理由

1. `Optional<T>` 是 Java 标准库提供的显式 absent 表示，调用方无法忽略。
2. 当前已有的 `findSingleByCapabilityType` 抛异常已是一个防护，但异常作为控制流不够优雅。
3. 与 `ModelCapabilities` 的声明式能力模型一致——能力的缺失是一个正常状态，不是异常。

## 参考文献

- [review-最终交叉评审报告.md](../20-评审/review-最终交叉评审报告.md) 郑-R1
- [02-多模型支持与模型路由.md](../10-设计/02-多模型支持与模型路由.md) §Capability
