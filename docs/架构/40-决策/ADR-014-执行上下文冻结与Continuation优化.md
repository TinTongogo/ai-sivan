# ADR-014：执行上下文冻结与 Continuation 优化

> **状态**：✅ 已决策
> **日期**：2026-06-05
> **作者**：首席架构师初审 → 系统架构组

---

## 背景

初审（review-第三方评审报告.md）中首席架构师张工发现两项风险：

**R1：`ExecutionContext` 冻结与不可变性未强制执行。** `ExecutionContext` 在递归传递过程中可能被中途修改，导致子树之间相互污染。`SequentialModeStrategy` 中每次 `next.execute()` 后调用 `updateContext(ctx, child)` 更新上下文——如果 `ExecutionContext` 是可变的，子树内部对上下文的修改会影响兄弟节点的执行。

**R2：`Continuation` 每次递归创建新实例的性能开销。** `Continuation` 作为函数式接口（`Flux<OrchestrationEvent> execute(child, ctx, depth)`），在递归遍历中每个子节点都创建一个新的 lambda 实例。1000 节点树 → 1000 个短生命周期对象 → GC 压力。

## 决策

### R1：sealed interface + FrozenContext

`ExecutionContext` 设计为 sealed interface（两种子类型），入口处强制冻结：

```java
sealed interface ExecutionContext permits MutableContext, FrozenContext {
    // ...只读查询方法
    <T> T get(String key);
    boolean isFrozen();

    /** 冻结当前上下文，返回不可变包装。调用后修改操作抛出异常。 */
    FrozenContext freeze();
}
```

- `MutableContext`：构建阶段使用，允许 `set()` / `remove()`
- `FrozenContext`：执行阶段使用，所有修改操作抛出 `UnsupportedOperationException`
- 入口处 `ForestExecutor.execute()` 首行调用 `ctx.freeze()`，确保执行路径全为不可变上下文

### R2：Continuation 提升为成员变量

`Continuation` 不再作为 lambda 每次创建，而是提升为 `ForestExecutor` 的成员变量，递归复用同一实例：

```java
@Component
class ForestExecutor {
    /** 递归回调，复用同一实例避免 GC 压力。 */
    private final Continuation continuation = (child, ctx, nodeCtx) ->
        executeNode(child, ctx, nodeCtx);
}
```

## 理由

1. **R1 防止上下文污染**：sealed interface + `freeze()` 是编译器+运行时双层保障。`SequentialModeStrategy` 中兄弟节点即使修改上下文，`FrozenContext` 也会抛出异常，立即暴露 bug。
2. **R1 编译器约束**：`sealed` 关键字限制只有两种子类型，外部无法扩展。
3. **R2 消除 GC 压力**：1000 节点树共享一个 Continuation 实例，lambda 对象从 1000 降为 1。对 Reactor 链的 GC 友好。

## 参考文献

- [review-第三方评审报告.md](../20-评审/review-第三方评审报告.md) §1.1 R1, R2
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §ExecutionContext, §Continuation
