# ADR-040：AgentMessageBus 生命周期与作用域

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

每个 `InnerGoal` 创建独立总线，但 CONDITIONAL 模式下同一阶段内的多个子节点是否共享总线未定义。若共享，可能造成意外通信干扰。

## 决策

每个 InnerGoal 创建独立的 AgentMessageBus 实例。CONDITIONAL 模式下同一阶段内的多个子节点**不共享总线**——每个子节点在 `executeNode()` 时获得自己的总线实例。总线随 InnerGoal 生命周期创建和销毁。

## 理由

避免 CONDITIONAL 模式下子节点间的意外通信干扰。跨树通信走领域事件而非 AgentMessageBus。

## 参考文献

- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §3.6.5
