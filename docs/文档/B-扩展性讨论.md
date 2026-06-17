# 扩展性讨论

## 核心问题

Sivan 的架构设计在哪些维度上具备扩展性？当需要支持新的 Agent 类型、新的编排模式、新的工具、新的前端交互方式时，分别需要改动哪些代码？

## 1. 节点类型扩展

### 1.1 添加一个新叶子节点类型

以添加一个"代码执行"（CodeExecutor）节点为例，展示全流程需要改动的文件：

| 步骤 | 文件 | 改动量 | 说明 |
|------|------|--------|------|
| 1 | `domain/forest/tree/node/` | - | 不需要新建类（复用 TaskNode） |
| 2 | `agent/forest/CodeLeafExecutor.java` | **新建 1 个文件** | 实现 LeafExecutor 接口 |
| 3 | `infra/forest/adapter/ForestRepositoryAdapter.java` | 添加 1 个 case | `createNode` switch 中加 `"code_exec"` 分支 |
| 4 | `application/forest/ForestExecutionOrchestrator.java` | 0 | 执行树构建不关心节点类型 |
| 5 | `ui/components/orchestration/ForestTree.vue` | 0 | 显示层通用 |

**总共：新建 1 个文件 + 修改 1 个 switch 分支。**

```java
// 步骤 2 的新建文件
@Component
public class CodeLeafExecutor implements LeafExecutor {
    @Override
    public String supportedType() { return "code_exec"; }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        String code = node.content();
        String language = node.metadataString("language");
        // 执行代码，返回结果
        String output = executeCode(code, language);
        return Flux.just(ForestEvent.detail(node.nodeId(), null, 
            ctx.accountId().toString(), output));
    }
}

// 步骤 3 的 switch 分支（ForestRepositoryAdapter.java）
case "code_exec" -> {
    var node = new TaskNode(nodeId, content, status);
    if (!metadata.isEmpty()) node.setMetadata(metadata);
    yield node;
}
```

### 1.2 添加一个新的编排模式

以添加"赛场模式"（TOURNAMENT——多个 Agent 独立执行后投票排序）为例：

| 步骤 | 文件 | 改动量 | 说明 |
|------|------|--------|------|
| 1 | `common/Mode.java` | 加 1 个枚举值 | `TOURNAMENT` |
| 2 | `agent/forest/mode/TournamentModeStrategy.java` | **新建 1 个文件** | 实现 ModeStrategy |
| 3 | `infra/forest/matcher/DefaultTreeMatcher.java` | 0（自动发现） | Spring @Component |
| 4 | `ui/components/orchestration/ForestTree.vue` | 添加显示逻辑 | TOURNAMENT 图标 |

## 2. 工具扩展

### 2.1 添加一个内部工具

```java
@Component
public class HttpToolRegistrar {
    private final ToolRegistry registry;

    public HttpToolRegistrar(ToolRegistry registry) {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of("url", Map.of("type", "string")),
            "required", List.of("url")
        );
        registry.register(
            new ToolSpec("http_get", "发送 HTTP GET 请求", schema),
            (tc, ctx) -> executeHttpGet(tc)
        );
    }

    private Mono<ToolResult> executeHttpGet(ToolCall tc) {
        // 执行 HTTP 请求
    }
}
```

**需要：新建 1 个注册类，在构造器中调用 `registry.register()`。**

### 2.2 添加一个 MCP 工具

不需要代码改动，也不需要修改配置文件。通过后端设置界面操作即可：

```
系统设置 → MCP 服务器管理 → 添加服务器
  → 输入：服务器名称、URL、认证信息（可选）
    → 提交 → McpServerConfigService.save()
      → McpConnectionManager 自动连接
        → 获取工具列表 → 注册到 ToolRegistry
```

实现链路：

| 层 | 文件 | 职责 |
|---|------|------|
| 前端 | `SystemSettingsModal.vue` | 设置界面，MCP 服务器管理表单 |
| API | `McpServerConfigController.java` | REST 端点：CRUD MCP 服务器配置 |
| 应用 | `McpServerConfigService.java` | 配置持久化 + 连接状态管理 |
| 领域 | `IMcpServerConfigRepository.java` | 仓储端口 |
| 基础设施 | `McpServerConfigRepositoryAdapter.java` | JPA 实现，持久化到 `mcp_server_configs` 表 |
| Agent | `McpConnectionManager.java` | 连接 MCP 服务器，动态注册和注销工具 |

MCP 服务器配置持久化到 `mcp_server_configs` 表，重启后自动恢复连接。添加新的 MCP 工具等同于在管理界面添加一个新的 MCP 服务器——**零代码、零配置**。

## 3. 存储扩展

### 3.1 添加一个新的存储后端

当前使用 PostgreSQL + pgvector，如果需要替换为其他存储：

```java
// 存储端端口在 domain 层定义（依赖倒置）
// infra 层提供具体实现
public interface ForestRepository {
    // 领域层定义的接口，不依赖任何数据库细节
    TreeNode findSubtree(String rootNodeId, UUID accountId);
    void saveTree(TreeNode root, UUID forestId, UUID accountId);
    // ...
}

// 当前实现：PostgreSQL + JPA
@Component
public class ForestRepositoryAdapter implements ForestRepository { ... }

// 未来实现：MySQL
@Component
@Profile("mysql")
public class ForestRepositoryMysqlAdapter implements ForestRepository { ... }

// 未来实现：DynamoDB
@Component
@Profile("dynamodb")
public class ForestRepositoryDynamoAdapter implements ForestRepository { ... }
```

### 3.2 向量存储扩展

```java
public interface IEmbeddingService {
    float[] embed(String text);
    boolean isAvailable();
}

// 当前：Qwen3-Embedding-2B（本地 vLLM）
// 未来：OpenAI Embedding API
// 未来：Cohere Embedding API
```

## 4. 模型扩展

### 4.1 添加一个新的 LLM 提供商

通过后台设置界面即可添加新的 LLM 提供商，**无需修改代码或配置文件**：

```
系统设置 → 模型提供商 → 添加提供商
  → 输入：提供商名称、API URL、模型名称、API Key（可选）
    → 提交 → LlmProviderService.upsertProvider()
      → 持久化到 llm_providers 表
        → 路由系统自动感知
```

实现链路：

| 层 | 文件 | 职责 |
|---|------|------|
| 前端 | `SystemSettingsModal.vue` | 设置界面，模型提供商管理 |
| API | `SettingsController.java` | REST 端点：CRUD 提供商、测试连接 |
| 应用 | `LlmProviderService.java` | 提供商的持久化和路由 |
| 数据库 | `llm_providers` 表 | 存储提供商配置（URL、模型名、API Key 等） |

支持提供商类型：
- `vllm` — vLLM 推理服务（默认）
- `openai` — OpenAI 兼容 API
- `ollama` — Ollama 本地推理

**每个提供商可以绑定一个"标签"（tag）**：`"default"`、`"embedding"`、`"reranker"`。路由系统根据任务类型自动选择对应的提供商。添加新提供商 = 在设置界面填一个表单。

### 4.2 添加一个新的模型适配器（需要代码改动）

如果 LLM 提供商使用的协议不在上述三种类型中（如 Anthropic、Google Vertex AI），则需要编写一个新的 `Model` 接口实现：

```java
@Component
@ConditionalOnProperty(name = "sivan.model.anthropic.enabled")
public class AnthropicModelAdapter implements Model {
    @Override
    public Flux<ModelResponse> stream(List<Msg> messages, List<ToolSpec> tools, ModelParams params) {
        // 调用 Anthropic API
    }
}
```

适配器实现后通过 `@Component` 注册，`DefaultModelRouter` 自动发现和路由。

## 5. 前端扩展

### 5.1 添加新的节点展示方式

`ForestTree.vue` 使用递归组件 + CSS 样式控制展示。要添加新的节点展示只需：

```vue
<!-- 在 ForestTree.vue 模板中添加新节点类型分支 -->
<span v-if="node.mode === 'TOURNAMENT'" class="mode-tournament">
  赛场 🏆
</span>
```

### 5.2 添加新的 SSE 事件类型

```typescript
// api/sse.ts 中添加事件处理
switch (event.type) {
  case 'response': handleText(event.content); break;
  case 'thinking': handleThinking(event.content); break;
  case 'progress': updateProgress(event.data); break;
  case 'stage_progress': updateStageProgress(event.data); break; // 新增
}
```

## 6. 编排策略扩展

`ModeStrategy` 接口为编排模式提供了统一的扩展点：

```java
public interface ModeStrategy {
    Mode supportedMode();
    Flux<ForestEvent> execute(ExecutableNode node, ExecutionContext ctx, 
                               ForestExecutor executor, EventSink sink);
}
```

添加新策略：
1. 实现 `ModeStrategy` 接口
2. 用 `@Component` 注册
3. 在 `TreeMatcher` 的 LLM prompt 中补充新模式的描述（可选）

不需要修改 `ForestExecutor`——它通过 `ModeDispatcher` 自动路由到对应的策略实现。

## 7. 扩展性汇总矩阵

| 扩展维度 | 核心抽象 | 新建文件 | 修改现有文件 | 总工作量 |
|---------|---------|---------|------------|---------|
| 新节点类型 | `LeafExecutor` | 1 | 1（switch） | ~100 行 |
| 新编排模式 | `ModeStrategy` | 1 | 1（枚举） | ~150 行 |
| 新内部工具 | `ToolRegistry.register()` | 1 | 0 | ~80 行 |
| 新 MCP 工具 | 无 | 0 | 0（改配置） | 0 行 |
| 新存储后端 | `ForestRepository` | 1 | 0（新 profile） | ~500 行 |
| 新 LLM 模型 | `Model` | 1 | 1（配置） | ~200 行 |
| 新前端视图 | 无 | 0 | 1（模板） | ~20 行 |

## 8. 扩展性设计原则总结

### 8.1 依赖倒置（DIP）

领域层定义端口（接口），基础设施层提供实现。替换实现不需要修改领域层代码。

```
sivan-domain ← 接口定义
sivan-infra  ← 接口实现（可替换）
```

### 8.2 策略模式 + Spring 自动注册

`LeafExecutor`、`ModeStrategy`、`FoldStrategy` 都使用策略模式 + `@Component` 自动注册。新增策略不需要修改注册逻辑。

### 8.3 统一存储的利弊

**利**：新节点类型不需要建新表、不需要改 SQL、不需要处理 JOIN。
**弊**：表结构增长，索引变多，需要部分索引和专用列治理。

### 8.4 分片对扩展性的影响

按 `account_id` 分片（推荐方案）不影响上述所有扩展点——因为分片是 infra 层的事情，domain 层代码不感知。唯一的例外是向量搜索的广播归并逻辑，需要应用层配合。
