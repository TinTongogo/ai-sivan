# ADR-058：CONDITIONAL 分支决策透明化

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

CONDITIONAL 模式下 LLM 决定下一阶段，用户只看到最终路径，看不到被跳过的分支，可能误以为系统忽略了某些任务。

## 决策

SSE 事件中增加 `branch_decision` 事件类型，每次 LLM 做出阶段决策时发送：

```json
event: branch_decision
data: {"chosen": "Phase 2: code_review", "skipped": ["Phase 2: unit_test"], "reason": "变更范围较小"}
```

前端将跳过的分支灰显，用户悬停可查看跳过理由。

## 理由

决策过程的透明性是建立用户信任的关键——用户需要知道系统为什么跳过了某些分支，而不是怀疑系统"忘了"。

## 参考文献

- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §ConditionalModeStrategy
