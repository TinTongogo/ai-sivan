# 上下文管理

## 核心问题

大语言模型的上下文窗口是有限的（通常 8K-128K tokens）。在长对话、多 Agent 协作、跨会话记忆召回的场景中，系统如何在有限的窗口内**保留最重要的信息、压缩冗余信息、并在需要时主动召回历史信息**？

## 1. 上下文管理的三维度

Sivan 的上下文管理从三个维度同时进行：

```
时间维度（对话历史）
  ├── HOT：最近 3 轮（原始消息，不压缩）
  ├── WARM：摘要压缩
  └── COLD：向量检索召回

空间维度（多 Agent 协作）
  ├── 根节点：完整上下文（system prompt + 历史 + 记忆）
  ├── 子节点（SEQUENTIAL）：累积上下文
  └── 子节点（PARALLEL）：独立上下文

生命周期维度（跨对话）
  ├── SESSION：5 小时（当前对话）
  ├── PROJECT：7 天（当前项目）
  └── USER：90 天（用户画像）
```

这三个维度不是独立的——**时间维度决定保留哪些历史，空间维度决定分发给哪个 Agent，生命周期维度决定哪些跨对话信息可用**。

---

## 2. 时间维度：对话内上下文

### 2.1 构建链路

每次用户请求，系统构建发送给 LLM 的完整消息列表：

```
PromptContextService.buildLlmMessages()
  ├── 1. 系统提示词（Agent 定义 + 技能）
  ├── 2. 最近 N 轮对话（HOT 层，游标分页加载）
  │     └── 按 sort_order 降序，取最后 3 轮
  ├── 3. 摘要上下文（WARM 层）
  │     └── HISTORY 压缩结果 + 自动摘要
  ├── 4. 记忆上下文（COLD 层）
  │     └── FlashbackScanner 召回的候选记忆
  ├── 5. 工具结果
  │     └── 上轮工具调用的输出
  └── 6. 当前消息
```

### 2.2 游标分页加载

消息查询使用游标分页，避免 OFFSET 的性能问题：

```java
// 查询比 beforeSortOrder 更早的消息
forestNodeJpaRepository.findMessagesBeforeSortOrder(
    conversationId, beforeSortOrder, PageRequest.of(0, limit));
```

每页 50 条，逐页向上扫描直到填满上下文预算。

### 2.3 消息的双重存储

每条消息在系统中存在两次：

| 存储位置 | 目的 | 用途 |
|---------|------|------|
| `forest_nodes(node_type='message')` | 完整持久化 | 历史查询、重新生成、审计 |
| `prebuiltMessages(List<Msg>)` | 运行时内存 | LLM 调用、执行树传递 |

`prebuiltMessages` 是 LLM 格式的消息，在构建执行树时生成，放在根 TaskNode 的 metadata 中（**仅在根节点，不递归复制**——Phase 1 治理的成果）。

---

## 3. 空间维度：执行树上下文

### 3.1 根节点上下文

根 TaskNode 持有完整的 `prebuiltMessages`——包括系统提示词、对话历史、记忆召回、当前消息。这是 Agent 看到的完整上下文。

### 3.2 子节点上下文

不同编排模式对上下文的处理不同：

| 模式 | 子节点上下文 | 数据流 |
|------|------------|--------|
| SEQUENTIAL | 父节点的输出累积 | `accumulatedContext` metadata 依次传递 |
| PARALLEL | 每个子节点独立上下文 | 各节点独立输出 → SynthesisNode 合并 |
| CONSENSUS | 每个子节点相同上下文 | 各节点独立分析 → SynthesisNode 投票合并 |
| CONDITIONAL | 满足条件的路径执行 | 条件判断后注入对应子节点 |
| HIERARCHICAL | 嵌套继承 | 每层叠加自己的上下文 |

### 3.3 SEQUENTIAL 的累积上下文

```java
// SequentialModeStrategy 中，每个节点执行后累积输出
StringBuilder accumulated = new StringBuilder();
for (var child : children) {
    // 注入已有累积
    child.metadata().put("accumulatedContext", accumulated.toString());
    
    // 执行子节点
    var result = executor.execute(child, ctx, sink);
    
    // 追加输出
    if (result.content() != null) {
        accumulated.append(result.content()).append("\n");
    }
}
```

这条链路保证了在流水线作业中，后续节点能"看到"前面节点的分析结果。

### 3.4 队友视角（Peers）

在 PARALLEL 和 CONSENSUS 模式中，每个子节点通过 `peers` metadata 知道其他节点正在做什么：

```java
// 注入队友名片
sb.append("## 团队队友\n");
sb.append("你可以用 send_agent_message 与他们通信\n");
for (Map<String, String> peer : peers) {
    sb.append("- ").append(peer.get("agentName"))
      .append(": ").append(peer.get("task")).append("\n");
}
```

这实现了**空间维度上的上下文共享**——Agent 知道队友的存在和职责，但不阻塞等待队友的结果。

---

## 4. 生命周期维度：跨对话上下文

### 4.1 三层记忆

| 层级 | 保留期 | 晋升条件 | 衰减因子 λ |
|------|--------|---------|-----------|
| SESSION | 5 小时 | 新建记忆默认 | 0.5 |
| PROJECT | 7 天 | SESSION 访问 >5 次 + 保留率 >0.5 | 0.05 |
| USER | 90 天 | PROJECT 访问 >15 次 + 保留率 >0.7 | 0.01 |

### 4.2 写入时机

记忆在以下时机自动写入：

```
1. 对话结束 → 压缩服务提取关键信息 → 存入 SESSION 记忆
2. 用户表达偏好 → ProfileLearner 提取 → 存入 USER 记忆
3. 工具执行成功/失败 → RouteFeedbackHandler 更新 Beta 参数
4. 节点执行完毕 → 本能模板提取特征 → 存入本能模式库
```

### 4.3 读取时机

```
每次用户输入：
  1. FlashbackScanner 扫描 = 候选记忆列表
  2. 按 relevanceScore 排序 = TOP-K
  3. 注入 MessageTree = 作为 system prompt 附加

  （仅 COLD 层主动召回，HOT/WARM 由对话历史自然携带）
```

### 4.4 遗忘曲线调度

`ForgettingCurveService` 每小时自动运行：
- 计算每条记忆的当前保留率
- 保留率 < ARCHIVE_THRESHOLD → 归档
- 访问次数 + 保留率满足晋升条件 → 升级

---

## 5. Token 预算管理

### 5.1 预算计算

```java
// 根据模型和场景分配预算
int contextLength = resolveContextLength(providerId, accountId);
// 例如：Qwen3-128K → 128K × 0.7 = 89.6K tokens
int maxPromptTokens = contextLength × budgetRatio(scene);
```

### 5.2 预算在各个上下文类型间的分配

| 上下文类型 | 优先级 | 分配策略 | 超出时的处理 |
|-----------|--------|---------|------------|
| 系统提示词 | 最高 | 固定，不可压缩 | 截断技能内容 |
| HOT 历史 | 高 | 最近 N 轮原始消息 | 降低 N |
| WARM 摘要 | 中 | 压缩后的摘要 | 缩短摘要长度 |
| COLD 记忆 | 低 | 向量召回的候选 | 减少候选数 |
| 工具结果 | 中 | 上次工具调用的输出 | 截断到 3000 字符 |

### 5.3 压缩触发

```
当前消息 token > 预算 → ConversationCompressionService.compress()
  → HOT 压缩：浓缩最近 N 轮的关键信息
    → 仍超 → WARM 压缩：对更早的历史自动摘要
      → 仍超 → COLD 压缩：归档旧记忆
```

---

## 6. 上下文管理的三个关键治理

### 6.1 运行时数据与持久化数据分离

**问题**：`prebuiltMessages`（全量 LLM 消息列表）躺在 metadata JSONB 中一起持久化。

**治理**：Phase 1 中定义了 `RUNTIME_METADATA_KEYS` 黑名单，在 `toEntity()` 序列化时过滤。

### 6.2 过滤字段从 JSONB 提升为列

**问题**：记忆的 level/archived/important 在 JSONB 中，全表加载后在 Java 中 filter。

**治理**：V30 迁移加专用列 + 部分索引，查询下推为 SQL WHERE。

### 6.3 prebuiltMessages 不递归传播

**问题**：`addRuntimeMetadata()` 将 prebuiltMessages 递归复制到每个子节点，每个子节点在 JSONB 中复制了一份全量历史。

**治理**：子节点递归时传 `null, null`，仅根节点持有 prebuiltMessages。

---

## 7. 全链路汇总

```
用户输入
  │
  ├── 1. PromptContextService 构建消息列表
  │     ├── 加载 HOT 历史（游标分页）
  │     ├── 加载 WARM 摘要（压缩结果）
  │     └── 加载 COLD 记忆（FlashbackScanner 召回）
  │
  ├── 2. ConversationCompressionService 预算检查
  │     ├── 超预算 → HOT 压缩
  │     ├── 仍超 → WARM 压缩
  │     └── 仍超 → COLD 归档
  │
  ├── 3. 构建执行树
  │     ├── 根节点持有 prebuiltMessages（完整上下文）
  │     └── 子节点按编排模式分配合适的上下文
  │
  ├── 4. Agent 执行
  │     ├── 根 Agent 使用完整上下文
  │     ├── 子 Agent 使用受限上下文 + A2A 通信
  │     └── 工具调用结果注入上下文
  │
  └── 5. 执行完成
        ├── 关键信息写入记忆（ProfileLearner）
        ├── 工具反馈写入 Beta 参数（RouteFeedbackHandler）
        └── 本能模式提取特征（InstinctPatternService）
```

---

## 8. 与同类系统的对比

| 维度 | Sivan | LangChain | 原生 ChatGPT |
|------|-------|-----------|-------------|
| 上下文容器 | Forest（执行树 + 消息 + 记忆 统一管理） | Chain（线性） | 无结构消息列表 |
| 多轮压缩 | HOT/WARM/COLD 三层 | ConversationBufferWindowMemory | 滑动窗口 |
| 记忆召回 | 遗忘曲线 + 情境闪现 | VectorStoreRetriever | 无 |
| Agent 间上下文 | A2A 消息总线 + SynthesisNode | Tool 通信 | 不适用 |
| 跨对话上下文 | SESSION→PROJECT→USER 晋升 | 需手动管理 | 有限（Custom Instructions） |
