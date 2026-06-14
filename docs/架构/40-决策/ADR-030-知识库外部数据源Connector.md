# ADR-030：知识库外部数据源 Connector 框架

> **状态**：✅ 已决策
> **日期**：2026-06-05
> **作者**：竞品分析 → 系统架构组

---

## 背景

竞品分析发现 **G4 差距**：Sivan 知识库只支持内部上传的文档和对话记录，无法搜索外部数据源（Slack、Confluence、Jira、飞书等）。对比 Glean、Notion AI 等竞品，企业用户需要搜索内部知识库的完整能力。

## 决策

引入 `KnowledgeConnector` 接口，支持两种同步模式：

```java
interface KnowledgeConnector {
    /** 连接器标识，如 "confluence"、"slack"、"jira" */
    String name();

    /** 同步模式 */
    SyncMode syncMode();

    /** 按需查询（不建立索引，每次实时搜索外部源） */
    List<Chunk> query(String query, int topK);

    /** 批量导入（将外部数据索引到内部向量库） */
    void sync(ProgressCallback callback);
}

enum SyncMode { ON_DEMAND, SYNC }
```

### 实现策略

| 模式 | 方式 | 适用场景 |
|------|------|---------|
| `ON_DEMAND` | 用户提问时实时查询外部 API，不建立本地索引 | Slack、Jira（数据量小、实时性要求高） |
| `SYNC` | 定时批量导入外部数据到内部向量库，本地检索 | Confluence、飞书（数据量大、可接受延迟） |

## 理由

1. 企业用户的内部知识搜索是刚性需求，竞品均已覆盖此能力。
2. 双模式适配不同外部源的数据特性——实时查询 vs 批量索引。
3. Connector 接口不限定协议，HTTP、gRPC、MCP 均可接入。

## 参考文献

- [review-竞品分析与产品差距.md](../20-评审/review-竞品分析与产品差距.md) G4
- [10-知识库与RAG.md](../10-设计/10-知识库与RAG.md) §5 Connector 框架
