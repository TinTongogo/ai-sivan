# ADR-028：Agent间通信引入 AgentMessageBus

> **状态**：✅ 已决策
> **日期**：2026-06-05
> **作者**：竞品分析 → 系统架构组

---

## 背景

竞品分析发现 **G1 差距**：Agent 间没有显式通信协议。对比 CrewAI 的 Agent 间消息传递，Sivan 当前的上下文隐式通过 `ExecutionContext` 传递，没有类似消息队列的机制支持实时 Agent 交互。

## 决策

在森林架构中增加 `AgentMessageBus`，作为 Agent 间通信的轻量通道：

```java
interface AgentMessageBus {
    <T> void publish(String topic, T message);
    <T> Flux<T> subscribe(String topic, Class<T> type);
}
```

- 走内存 EventBus（Reactors `Sinks.many()`），不引入消息队列中间件
- 节点通过 `topic` 订阅/发布，topic 按 `forestId/nodeId` 命名空间隔离
- 与 `ExecutionContext` 正交：上下文传递状态，消息总线传递事件

## 理由

1. CrewAI 等竞品已验证 Agent 间通信是高级编排场景的刚性需求。
2. 内存 EventBus 无外部依赖，适合 v2.0 单体架构。
3. AgentMessageBus 与 EventSink 互不重叠——EventSink 是输出通道（向外），MessageBus 是内部通信（向内）。

## 参考文献

- [review-竞品分析与产品差距.md](../20-评审/review-竞品分析与产品差距.md) G1
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §3.6 AgentMessageBus
