# ADR-045：metadata JSONB 宽松存储策略

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

metadata 列只允许 predefined keys（`hitl`、`condition`、`agent_name` 等），扩展新节点类型时需修改校验代码，违反开闭原则。

## 决策

| 层 | 策略 |
|----|------|
| PostgreSQL | JSONB 列不设 CHECK 约束，接受任意 key-value |
| 业务层 | 各 LeafExecutor 消费时自行校验 key 是否存在 |
| 约定 | key 使用 `snake_case`，value ≤ 1 KB，≤ 10 个键值对 |

## 理由

宽松存储 + 业务层约束在灵活性和安全性之间取得平衡。新增节点类型不需要修改数据库或校验代码。

## 参考文献

- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §metadata JSONB 校验策略
