# forest_nodes 分库分表方案

## 背景

`forest_nodes` 是 Sivan 中唯一的核心存储表，承载了执行树、对话消息、记忆条目三类数据。当前单表架构在以下场景会面临扩展压力：

| 场景 | 预估数据量 | 瓶颈 |
|------|-----------|------|
| 单用户 1000+ 对话，每轮 10 条消息 | ~10K rows/user | 无压力 |
| 1000 活跃用户，日均 10 轮对话 | ~36.5M rows/年 | 消息查询开始变慢 |
| 10000 活跃用户，日均 10 轮对话 | ~365M rows/年 | 索引膨胀、写入吞吐不足 |
| 向量搜索 + 消息检索同时高频 | ~1B rows | IO 竞争、连接池耗尽 |

> **当前阶段不需要分库分表**——以下方案是面向未来 10x-100x 数据增长的架构储备。

---

## 分库分表的核心约束

`forest_nodes` 有三种差异极大的查询模式，分片策略必须同时满足：

| 查询模式 | 特征 | 压力类型 | 代表查询 |
|---------|------|---------|---------|
| **树加载** | 单 forest_id 内的递归 CTE，读取整棵子树 | 偶发、深读 | `findSubtree(rootNodeId)` |
| **消息分页** | 按 sort_order 游标翻页，频繁、浅读 | 高频、浅读 | `findLatestMessages()` |
| **向量搜索** | 全库级别 cosine 相似度排序，TOP-K | 全局、计算密集 | `semanticSearchMemory()` |

**核心约束 1：递归 CTE 不能跨分片**
```sql
WITH RECURSIVE subtree AS (
    SELECT * FROM forest_nodes WHERE node_id = ?  -- 锚点必须在单个分片上
    UNION ALL
    SELECT fn.* FROM forest_nodes fn
    INNER JOIN subtree st ON fn.parent_node_id = st.node_id
    WHERE st.depth < 1000
)
```
→ 一棵树的所有节点必须在同一分片上。

**核心约束 2：向量搜索是全库操作**
`WHERE node_type = 'memory' ORDER BY vector <=> ? LIMIT 10` 需要在所有分片上并行执行后归并。

**核心约束 3：account_id 是天然隔离边界**
95%+ 的查询是用户级的（携带 `account_id`），跨用户查询极少（仅遗忘曲线调度等定时任务）。

---

## 方案一：按 account_id 分片（推荐）

### 分片策略

```
shard = hash(account_id) % N
```

- 同一用户的所有数据在同一分片上
- 递归 CTE 天然不跨分片
- 事务不需要跨分片协调

### 分片键设计

```sql
-- 中间件层（ShardingSphere / MyCat）配置
sharding-algorithms:
  account-hash:
    type: HASH_MOD
    props:
      sharding-count: 8  -- 初始 8 个分片

-- 默认分片键：account_id
tables:
  forest_nodes:
    actual-data-nodes: ds$->{0..7}.forest_nodes_$->{0..7}
    table-strategy:
      standard:
        sharding-column: account_id
        sharding-algorithm: account-hash
```

### 路由规则

| 查询条件 | 路由策略 | 涉及分片数 |
|---------|---------|-----------|
| `account_id = ?` | 精确路由 | 1 |
| `account_id = ? AND forest_id = ?` | 精确路由（account_id 决定分片） | 1 |
| `forest_id = ?`（无 account_id） | 需要广播或从映射表查 account_id | N（所有分片） |
| `node_type = 'memory'` 向量搜索 | 广播 + 归并 | N |

### 向量搜索的跨分片归并

```java
// 伪代码：向量搜索跨分片执行
List<float[]> results = shards.parallelStream()
    .map(shard -> shard.execute(
        "SELECT * FROM forest_nodes WHERE node_type = 'memory' " +
        "ORDER BY vector <=> ? LIMIT ?", queryVec, topK * 2))
    .flatMap(List::stream)
    .sorted(byCosineDistance)
    .limit(topK)
    .collect(toList());
```

### 优点
- 递归 CTE 不跨分片——最重要的架构约束得到满足
- 用户级隔离——一个用户的数据操作不影响其他用户
- 事务简单——不需要分布式事务

### 缺点
- 热用户问题：活跃用户所在的分片压力远大于非活跃用户
- 向量搜索需要广播到全部分片
- 定时任务（遗忘曲线）需要扫描所有分片

### 热用户应对

```java
// 分片内再按 forest_id 子分区
// 热用户单独一个子分区，不影响其他用户
Map<UUID, Integer> shardMap = loadBalanceTable.getShardMap();
Integer shard = shardMap.computeIfAbsent(accountId, 
    id -> activeRouter.allocateLeastLoadedShard(id));
```

---

## 方案二：双层分片（forest_id 级路由）

### 设计

```
第一层：按 account_id 分片（确定物理库）
第二层：按 forest_id 在分片内做 hash 子分区（确定物理表）
```

```sql
-- 物理分片 = hash(account_id) % 库数
-- 逻辑子表 = hash(forest_id) % 表数
-- 最终: ds_{account_shard}.forest_nodes_{forest_subshard}
```

### 优点
- 同一用户的数据可以分布在多张表上（每个 forest 独立）
- 避免热用户打垮单表

### 缺点
- 跨 forest 的查询（如统计）需要扫描同一分片内的所有子表
- 增加连接池数量（每个物理库仍有多个连接）

---

## 方案三：读写分离 + 垂直拆分

### 设计

将 `forest_nodes` 按 `node_type` 在逻辑层拆分为三个视图，物理上可以是同一张表（当前），也可以是分离的表（未来的分片）：

```
forest_nodes（写库 + 主分片）
├── execution_node 视图（node_type IN ('task','inner_goal','synthesis')）
├── message_node 视图（node_type = 'message'）
└── memory_node 视图（node_type = 'memory'）
     └── 读副本：vector 索引专用（HNSW 索引只读实例）
```

### 读副本的向量搜索

```sql
-- 主库写、从库读
-- 向量索引只建在读库上，不影响写库性能
-- 主库同步到从库通过 PostgreSQL streaming replication
```

### 优点
- 向量搜索不阻塞主库写入
- 可以为不同视图设置不同的分片策略

### 缺点
- 需要维护读写一致性
- 架构复杂度增加（需要配置复制、故障切换）

---

## 实际路线图

### Phase 0：单表优化（当前，无需分片）

```
优化手段：
├── 部分索引（V30 已做）—— memory 的 level/archived/important 过滤走索引
├── 覆盖索引——减少回表查询
├── 表空间分离——WAL 日志和数据文件分离
└── 连接池调优——HikariCP 按业务优先级分配
```

### Phase 1：读写分离（数据量 ~100M 时引入）

```
┌─────────────────────────────────────┐
│  Pgpool-II / pgcat                    │
│  ┌────────────┐  ┌────────────┐     │
│  │  主库 RW   │  │  从库 RO   │     │
│  │ forest_nodes│  │ forest_nodes│    │
│  │            │  │ vector索引  │     │
│  └────────────┘  └────────────┘     │
└─────────────────────────────────────┘
  写操作：持久化执行树、消息、更新状态
  读操作：向量搜索、消息查询、树加载
```

### Phase 2：按 account_id 分片（数据量 ~1B 时引入）

```
                          ┌── 协调节点（无状态路由）
                          │  分片键：account_id
        ┌─────────────────┼─────────────────┐
        │                 │                 │
    ds_0(account%8=0)  ds_1(account%8=1)  ... ds_7(account%8=7)
        │                 │                 │
  forest_nodes        forest_nodes        forest_nodes
        │                 │                 │
  部分索引             部分索引             部分索引
  vector HNSW         vector HNSW         vector HNSW

向量搜索：并行广播到 8 个分片 → 归并 TOP-K
```

### Phase 3：冷热分离（数据量 ~10B 时引入）

```
forest_nodes
├── HOT（当前 30 天）
│   └── SSD 存储，高 IOPS
├── WARM（30-365 天）
│   └── SATA SSD，压缩存储
└── COLD（1 年以上）
    └── 归档到对象存储（S3/MinIO），不直接服务查询
```

使用 PostgreSQL 表分区（PARTITION BY RANGE based on created_at）：

```sql
CREATE TABLE forest_nodes (
    ...,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

CREATE TABLE forest_nodes_2026q1 PARTITION OF forest_nodes
    FOR VALUES FROM ('2026-01-01') TO ('2026-04-01');
CREATE TABLE forest_nodes_2026q2 PARTITION OF forest_nodes
    FOR VALUES FROM ('2026-04-01') TO ('2026-07-01');
-- ...
```

---

## ShardingSphere 配置示例

### 按 account_id 分片

```yaml
# shardingsphere-config.yaml
rules:
  - !SHARDING
    tables:
      forest_nodes:
        actualDataNodes: ds_${0..7}.forest_nodes
        tableStrategy:
          standard:
            shardingColumn: account_id
            shardingAlgorithmName: account_mod
        keyGenerateStrategy:
          column: node_id
          keyGeneratorName: uuid
    shardingAlgorithms:
      account_mod:
        type: HASH_MOD
        props:
          sharding-count: 8
    defaultDatabaseStrategy:
      standard:
        shardingColumn: account_id
        shardingAlgorithmName: account_mod
```

### 广播表配置（vector 搜索的归并）

```yaml
    broadcastTables:
      - routing_decisions    # 路由决策表数据量小，全分片复制
      - forest_execution_logs  # 执行日志按 account_id 分片
```

---

## 分片对代码的影响

### 需要修改的查询

```java
// 当前：直接 JPA 查询
forestNodeJpaRepository.findByForestIdAndNodeTypeOrderBySortOrder(forestId, "memory");

// 分片后：中间件自动路由（如果查询带有 account_id）
forestNodeJpaRepository.findByAccountIdAndForestIdAndNodeTypeOrderBySortOrder(
    accountId, forestId, "memory");
// 注意：JPA 方法必须显式包含 account_id 作为查询参数
// 中间件根据 account_id 的值计算分片
```

### 向量搜索的改造

```java
// 当前：单库单表
forestNodeJpaRepository.semanticSearchMemory("memory", vecStr, topK);

// 分片后：广播到所有分片，归并结果
// 方案 A（推荐）：中间件层自动广播
//   配置 hint sharding 强制全分片查询
// 方案 B：应用层手动广播
List<MemoryEntity> allResults = new ArrayList<>();
for (int i = 0; i < shardCount; i++) {
    hintManager.setDatabaseShardingValue(i);
    allResults.addAll(memoryRepo.search(accountId, vecStr, topK * 2));
}
return allResults.stream()
    .sorted(byCosineDistance)
    .limit(topK)
    .collect(toList());
```

### 定时任务改造

```java
// 遗忘曲线调度：需要扫描所有分片的所有用户
// 改造前：
List<MemoryEntry> allNonArchived = memoryRepository.findAllNonArchived();

// 改造后（逐分片扫描）：
for (int shard = 0; shard < shardCount; shard++) {
    hintManager.setDatabaseShardingValue(shard);
    List<UUID> accountsInShard = accountRepository.findAllIdsByShard(shard);
    for (UUID accountId : accountsInShard) {
        processAccount(accountId);
    }
}
```

---

## 不推荐方案及原因

### ❌ 按 node_type 分片

```
forest_nodes_task     — task/inner_goal/synthesis 节点
forest_nodes_message  — message 节点  
forest_nodes_memory   — memory 节点
```

**反对理由**：破坏递归 CTE 跨类型查询的能力。执行树中 `inner_goal → task → message` 横跨三种类型，如果分到三张表就无法用一条 CTE 加载整棵树。

### ❌ 按 forest_id 分片（无 account_id 层）

```
shard = hash(forest_id) % N
```

**反对理由**：无法解决向量搜索的全局查询问题（memory 没有 forest_id 约束）。

---

## 总结推荐

| 阶段 | 策略 | 触发条件 |
|------|------|---------|
| 当前 | 单表 + 部分索引优化 | ~10M rows |
| Phase 1 | 读写分离 + 从库向量索引 | ~100M rows |
| Phase 2 | 按 account_id 分片（8-16 分片） | ~1B rows |
| Phase 3 | 冷热分区 + 对象存储归档 | ~10B rows |

**核心原则**：不牺牲递归 CTE 能力、不增加应用层的分布式事务复杂度、向量搜索通过广播 + 归并解决。
