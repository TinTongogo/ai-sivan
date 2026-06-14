# ADR-047：forest_nodes 批量写入优化

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

高频场景下每个节点独立 `INSERT ... ON CONFLICT UPDATE` 产生大量行锁和 WAL 日志。

## 决策

| 措施 | 说明 | 依赖 |
|------|------|------|
| 批量 flush | ProgressHeartbeat 每 5s 统一 flush 脏节点 | 已有 ProgressHeartbeat |
| 批量 SQL | `INSERT INTO ... VALUES (...), (...), ... ON CONFLICT DO UPDATE` | Phase 0 |
| 异步写入 | 非关键状态写入独立表 | Phase 1 |

## 理由

PARALLEL 模式下子节点高频完成，每条独立写入的 WAL 开销和行锁竞争不可忽视。批量 flush 将 N 次写入合并为 1 次。

## 参考文献

- [09-持久化与恢复.md](../10-设计/09-持久化与恢复.md) §批量写入优化
