# ADR-020：ForestNode 横切关注点——优先使用 metadata，审慎创建新接口

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：交叉评审 → 系统架构组

---

## 背景

交叉评审发现 **刘-R2 关注点**：当前 4 个接口（`TreeNode` / `ExecutableNode` / `CompressibleNode` / `ContentNode`），如果将来扩展出 `AuditableNode`、`DebuggableNode`、`ExportableNode` 等，接口数量会线性增长。

## 决策

横切关注点优先放入 `metadata`（`Map<String, Object>`），审慎创建新接口：

```java
// ✅ 横切关注点 → metadata
node.metadata().put("auditEnabled", true);
node.metadata().put("debugLevel", "verbose");

// ❌ 不优先创建新接口
// interface AuditableNode { boolean auditEnabled(); }
// interface DebuggableNode { String debugLevel(); }
```

**创建新接口的门槛**：
1. 该属性被至少 2 个遍历器（Visitor）或策略依赖
2. 该属性有**行为**（方法），不仅是数据
3. 该接口有至少 2 个具体节点类实现

## 理由

1. `metadata` 的核心优势是灵活——新增关注点不改接口、不涉及编译、不影响现有节点。
2. 代价是失去编译期类型检查——`metadata.get("auditEnabled")` 可能在运行时返回 null。
3. 平衡点：数据属性走 metadata，行为契约走接口。

## 参考文献

- [review-最终交叉评审报告.md](../20-评审/review-最终交叉评审报告.md) 刘-R2
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §ISP
