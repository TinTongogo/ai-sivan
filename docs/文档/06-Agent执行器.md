# 第六章：Agent 执行器

## 核心问题

一个 TaskNode 被提交后，系统如何让它"执行"？Agent 如何思考、调用工具、与其他 Agent 通信？

## 6.1 ReAct 循环

### 6.1.1 循环结构

`AgentLeafExecutor.reactLoop()` 实现了标准的 ReAct（Reasoning + Acting）模式：

```
LOOP:
  1. 将消息列表（system prompt + 历史 + 当前任务 + A2A 消息）发给 LLM
  2. LLM 返回文本 和/或 工具调用
  3. 如果有工具调用：
     a. send_agent_message → 发布到 A2A 消息总线
     b. 其他工具 → 执行并收集结果
     c. 将工具结果追加到消息列表
     d. 回到步骤 1
  4. 如果没有工具调用（纯文本回复）：
     a. 文本视为最终输出
     b. 退出循环
```

### 6.1.2 轮数限制

```java
private static final int MAX_TOOL_OUTPUT_CHARS = 3000;
@Value("${sivan.agent.max-rounds:200}")
private int maxRounds;
```

- 默认最多 200 轮工具调用
- 每轮工具输出截断到 3000 字符（防止上下文膨胀）

### 6.1.3 流式输出

每轮 ReAct 循环通过 `Flux<ForestEvent>` 持续发射事件：
- `DETAIL`：文本内容块（实时推送到前端）
- `THINKING`：思考过程
- `TOOL_CALL`：工具调用记录
- `TOOL_RESULT`：工具执行结果
- `MILESTONE`：Token 用量汇总

## 6.2 消息构建

### 6.2.1 消息列表的组成

`buildMessages()` 构建 LLM 调用的消息列表：

1. **System Prompt**：Agent 定义的系统提示词 + 匹配的技能内容 + 输出规范
2. **prebuiltMessages**：预构建的对话历史（包含之前的用户/助理消息）
3. **上下文累积**：如果是 SEQUENTIAL 子任务，注入 `accumulatedContext`
4. **队友名片**：多 Agent 协作时注入同行 Agent 的描述
5. **A2A 消息**：待处理的 Agent 间消息

### 6.2.2 Agent 提示词解析

```java
String agentName = node.metadataString("agentName");
String agentSystemPrompt = resolveAgentSystemPrompt(node, agentName);
```

`resolveAgentSystemPrompt` 加载：
1. Agent 定义中的 `systemPrompt` 字段
2. `_matchedSkillIds` 对应的技能内容（组合式路由的结果）
3. 输出规范模板

### 6.2.3 prebuiltMessages 的传递

prebuiltMessages 是 `ForestConversationService` 在构建执行树时预先准备好的 LLM 消息列表。它通过 `addRuntimeMetadata()` 注入到 TaskNode 的 metadata 中，仅在根节点持有（Phase 1 修复），不再递归复制到子节点。

## 6.3 工具调用

### 6.3.1 工具解析

```java
List<ToolSpec> tools = toolRouter.resolve(adapter, taskContent, execCtx);
if (tools.isEmpty()) {
    var rawSpecs = node.metadataList("preferredToolSpecs");
    // 使用预配置的工具列表
}
```

工具来源优先级：
1. `ToolRouter` 按叶子类型路由工具
2. metadata 中的 `preferredToolSpecs`（降级）

### 6.3.2 工具执行

`executeSingleTool()` 处理每个工具调用：

```java
var executor = toolRegistry.find(tc.name());
// 内部工具（file_*/bash）：注入 _fileRootPath 和 _archived
// 外部工具（MCP）：走 McpToolProvider
// 超时：5 分钟
var result = executor.execute(enhancedTc, toolCtx)
    .timeout(Duration.ofMinutes(5))
    .onErrorResume(e -> Mono.just(new ToolCallResult(id, name, false, e.getMessage())));
```

### 6.3.3 参数注入

内部文件/命令工具自动注入运行时参数：
- `_fileRootPath`：项目文件根目录（metadata 中的相对路径 + 全局 basePath）
- `_archived`：归档只读标记
- `_accountId`：账户 ID
- `_kbNames`：绑定的知识库 ID 列表

这些参数从 metadata 中读取，构造 `ExecutionContext` 传入工具。

## 6.4 Agent-to-Agent 通信

### 6.4.1 AgentMessageBus

基于 Reactor `Sinks.Many` 的内存消息总线：

```java
AgentMessageBus bus = ForestExecutor.activeBus();
var agentSub = bus.subscribe(agentId).subscribe(pendingMessages::add);
var broadcastSub = bus.subscribe("broadcast").subscribe(pendingMessages::add);
```

### 6.4.2 消息生命周期

```
Agent A.send_agent_message(target=B, content="...")
  → bus.publish(msg)
    → B 的订阅者收到消息
      → 下一轮 ReAct 循环 injectPendingA2AMessages()
        → B 看到消息，回复
          → bus.publish(reply)
            → A 的订阅者收到回复
```

### 6.4.3 持久化

A2A 消息持久化到 `forest_agent_messages` 表：
- `forest_id`、`scope_node_id`（当前执行树的根节点 ID）
- `source_agent`、`target_agent`
- `topic`（相关性 ID）
- `message_type`（REQUEST/RESPONSE/BROADCAST/DELEGATE）
- `payload`（消息内容）

## 6.5 执行流程全链路

```
ForestConversationService.send()
  → ForestExecutionOrchestrator.buildTree()
    → ForestExecutionOrchestrator.executeTree()
      → ForestExecutor.execute()
        → TreeMatcher 匹配编排模式 → 构建 InnerGoalNode
          → 递归遍历节点，为叶子分配 LeafExecutor
            → AgentLeafExecutor.reactLoop()
              → LLM 调用 → 文本 + 工具调用
                → 工具执行
                  → A2A 通信（跨 Agent）
                    → 最终文本输出
                      → ForestEvent → SSE → 前端
```

## 关键设计决策

- **为什么用 Flux 而不是直接返回？** 流式输出需要在毫秒级推送到前端，等全部执行完再返回会使用户等待数十秒
- **为什么工具要有超时？** LLM 可能陷入死循环（一直调用同一个出错的工具），超时是最后的保护
- **为什么 prebuiltMessages 不递归复制？** 每个子节点不需要完整的对话历史，只需要自己的 taskContent 和 systemPrompt
