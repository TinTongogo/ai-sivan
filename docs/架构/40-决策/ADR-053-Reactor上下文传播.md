# ADR-053：Reactor 上下文传播与 traceId 绑定

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

`StructuredLogging` 通过 `SpanContext.currentTraceId()` 静态方法获取 traceId。Reactor 线程切换（`subscribeOn`/`publishOn`）时静态方法无法感知 Reactor `Context`，可能导致 traceId 丢失。

## 决策

traceId 通过 Reactor `Context` 传递而非静态方法：

```java
Mono.defer(() -> executeNode(root, ctx))
    .contextWrite(ctx -> ctx.put("traceId", SpanContext.currentTraceId()));
```

生产环境开启 `Hooks.enableAutomaticContextPropagation()`。

## 理由

Reactor Context 是响应式链中传递请求范围上下文的唯一可靠方式。静态方法在线程池调度场景下不可靠。

## 参考文献

- [12-可观测性.md](../10-设计/12-可观测性.md) §2.2
