# Sivan v2.0 实现计划

> 日期：2026-06-05
> 基于：全部 26 份设计文档 + 4 轮评审

---

## 一、模块结构

```
ai-sivan/
├── sivan-common/              # 共享基类
│   ├── event/                 # 00-共享事件（所有领域事件 record）
│   ├── exception/             # DomainException 基类 + ApiError
│   ├── i18n/                  # 国际化 t() 工具
│   └── util/                  # 通用工具
│
├── sivan-domain/              # 领域实体（纯 POJO，无框架依赖）
│   ├── account/               # 00 账号与项目
│   │   ├── Account            # 账号实体
│   │   ├── Project            # 项目实体
│   │   └── AuthToken          # JWT 令牌值对象
│   └── forest/                # 01 核心域
│       ├── ForestNode         # TreeNode / ExecutableNode / CompressibleNode / ContentNode
│       ├── InnerGoalNode
│       ├── TaskNode / SynthesisNode / MessageNode
│       ├── Forest             # 聚合根
│       ├── ExecutionContext / NodeContext
│       ├── ForestEvent / Progress
│       └── Service / ModeStrategy / LeafExecutor 接口
│
├── sivan-infrastructure/       # 基础设施（框架依赖在此层）
│   ├── persistence/
│   │   ├── ForestRepositoryAdapter  # 09 持久化
│   │   └── Flyway 迁移脚本
│   ├── account/               # 00 账号与项目
│   │   ├── JwtTokenProvider
│   │   ├── OAuthClient
│   │   ├── ShortIdGenerator
│   │   ├── AccountRepositoryAdapter
│   │   └── ProjectRepositoryAdapter
│   ├── model/                 # 02 多模型
│   │   ├── OpenAiAdapter / ClaudeAdapter / OllamaAdapter
│   │   ├── ModelRegistry / ModelRouter
│   │   └── CostTracker / RoutingLogger
│   ├── security/              # 05 沙箱安全
│   │   ├── SandboxManager / Policy
│   │   ├── SecretStore / AuditedSecretStore
│   │   └── OutputValidator
│   ├── template/              # 03 本能模板
│   │   ├── TreeMatcher
│   │   ├── TemplateStore
│   │   └── FeedbackHandler / ExplorationScheduler
│   ├── compression/           # 04 对话压缩
│   │   ├── ForestCompressor
│   │   └── BudgetAllocationStrategy
│   ├── tool/                  # 07 工具感知
│   │   ├── ToolRegistry
│   │   ├── McpToolProvider
│   │   ├── SkillMdToolProvider
│   │   └── McpDiscoveryService
│   ├── memory/                # 13 记忆闪现
│   │   └── FlashbackEngine
│   └── agent/                 # Agent 执行
│       └── AgentLeafExecutor
│
├── sivan-web/                 # Web 层
│   ├── controller/            # 08 API 契约
│   │   ├── auth/             # 00 认证端点
│   │   ├── account/          # 00 账号管理
│   │   └── project/          # 00 项目管理
│   ├── sse/                   # SSE 流
│   └── config/               # 安全配置、CORS、JWT
│
├── sivan-ui/                  # 15 前端
│   ├── components/ForestTree.vue
│   ├── components/GoalCard.vue
│   ├── components/OnboardingGuide.vue
│   └── composables/useSseStream.ts
│
└── docs/                      # 设计文档
    └── 架构/2.0/
```

---

## 二、实现阶段

### Phase -1：地基前的地基（第 0 周，与 Phase 0 重叠）

```
目标：账号/项目体系先行，为所有后续 Phase 提供 accountId
交付：认证授权 + 项目 CRUD + ShortID
```

| Day | 任务 | 产出 |
|-----|------|------|
| 1 | Account 实体 + AccountRepository + Flyway 建表 | 可注册新用户 |
| 2 | AuthService：注册/登录/JWT 签发 + OAuth 2.1 流程 | 可登录获取 token |
| 3 | Project 实体 + ProjectRepository + ShortIdGenerator | 可创建/列出项目 |
| 4 | API Gateway 认证过滤器 + 目录初始化 | 所有请求带 accountId，项目自动创建目录 |

**验收标准**：
- 注册 → 登录 → JWT → 携带 token 请求受保护的 API → 成功
- 创建项目 → 生成 shortId → 自动创建目录结构 → 可查询
- 归档 → 只读 → 恢复 → 可写
- OAuth（Google/GitHub）登录流程跑通

### Phase 0：原型验证（第 1 周）

```
目标：验证核心假设，风险先行
交付：一个可运行的 SEQUENTIAL 模式 1000 节点原型
```

| Day | 任务 | 产出 |
|-----|------|------|
| 1 | 创建 Maven 模块结构 + 导入设计文档中的 domain 类 | `sivan-common` + `sivan-domain/forest` |
| 2 | `ForestNode` 接口家族 + `ForestRepository` + Flyway 建表 | 可建 1000 节点树到 DB |
| 3 | `ForestExecutor` + `SequentialModeStrategy` + `AgentLeafExecutor`（调 LLM） | 一条完整链路：输入→执行→输出 |
| 4 | 1000 节点压测：内存、CTE、Reactor 链 GC | `arc-09` 验证通过/不通过 |

**验收标准**：
- 1000 节点树内存增量 < 5MB
- CTE 查询 1000 节点 < 50ms
- 全链路执行完成无 Full GC
- 如不通过 → 先解决问题再进 Phase 1

### Phase 1：核心引擎（第 2-3 周）

```
目标：全部 5 种 mode + A2A + Basic LeafExecutor
交付：可执行任意拓扑结构的 GoalTree
```

| 模块 | 内容 | 预估 |
|------|------|------|
| ModeDispatcher | 5 种 ModeStrategy + Registry | 3 天 |
| A2A 通信 | AgentMessageBus | 2 天 |
| LeafExecutor | AgentLeafExecutor（核心） | 2 天 |
| 事件系统 | 领域事件 + NodeStatusChanged 订阅者 | 1 天 |
| ExecutionContext | freeze 机制 + 测试 | 1 天 |

### Phase 2：能力层（第 4-5 周）

```
目标：补齐工具、安全、多模型、提示词
交付：Agent 可以安全地调用外部工具和多种模型
```

| 模块 | 内容 | 预估 |
|------|------|------|
| 02 多模型 | ProviderAdapter x 3 + ModelRouter + CostTracker | 3 天 |
| 05 沙箱 | SandboxManager + Policies + SecretStore + Validator | 3 天 |
| 06 提示词 | PromptAssembler + Persona + PromptPack | 2 天 |
| 07 工具 | ToolRegistry + McpToolProvider + McpDiscoveryService | 3 天 |

### Phase 3：交互层（第 6-7 周）

```
目标：用户可见、可用
交付：Web 前端 + API + 知识库 + Flashback
```

| 模块 | 内容 | 预估 |
|------|------|------|
| 08 API | SSE/REST/WebSocket Controller | 2 天 |
| 10 知识库 | SearchKBNode + ConnectorRegistry | 3 天 |
| 13 Flashback | FlashbackEngine + MemoryStore | 2 天 |
| 15 前端 | ForestTree.vue + GoalCard + OnboardingGuide | 3 天 |

### Phase 4：质量（第 8-9 周）

```
目标：能上线
交付：测试覆盖 + 可观测性 + i18n + 部署脚本
```

| 模块 | 内容 | 预估 |
|------|------|------|
| 11 测试 | MockContinuation + PolicyTest + E2E | 3 天 |
| 12 可观测性 | 指标 + 日志 + 链路追踪 | 2 天 |
| i18n | 中文/英文资源文件 + 前端接入 | 2 天 |
| 部署 | Docker Compose + 启动脚本 | 1 天 |
| Bug 修复 | 集成测试发现的缺陷 | 2 天 |

---

## 三、Day 1 怎么做

### 第 1 步：建模块

```bash
# 从 GitHub 拉空模板或手动创建
ai-sivan/
├── pom.xml                    # parent POM，Spring Boot 4.0.4 + JDK 21（编译）/ 25（运行时）
├── sivan-common/pom.xml
├── sivan-domain/pom.xml
├── sivan-infrastructure/pom.xml
└── pom.xml
```

### 第 2 步：写 domain 类（半天）

先建 `00-账号与项目管理` 的 domain 类（新建 `sivan-domain/account/` 包）：

```
Account.java
Project.java
AuthToken.java（值对象）
AccountRepository.java（接口）
ProjectRepository.java（接口）
```

再建 `01-森林架构` 的 domain 类，从 `01-森林架构.md` 复制代码：

```
TreeNode.java（interface）
ExecutableNode.java（interface extends TreeNode）
InnerGoalNode.java（implements ExecutableNode）
TaskNode.java（implements ExecutableNode）
ExecutionContext.java（含 freeze 机制，已含 accountId）
NodeContext.java（depth + span）
ForestEvent.java（事件 record 族）
Progress.java
```

不要写 infra，不要写 service，不要写 controller——**只写纯 POJO**。可以编译通过就算完成。

### 第 3 步：写 Flyway + Repository（半天）

```
V1__create_forests.sql
V2__create_forest_nodes.sql
ForestRepository.java（interface）
ForestRepositoryAdapter.java（实现）
```

### 第 4 步：写 ForestExecutor + Sequential（半天）

从 `01-森林架构.md` 复制：

```
ModeStrategy.java（interface）
SequentialModeStrategy.java（最简单的模式）
ForestExecutor.java（递归遍历）
LeafExecutor.java（interface）
AgentLeafExecutor.java（只调 LLM，不调工具）
```

### 第 5 步：串联验证（半天）

写一个 `@SpringBootTest`：

```java
@Test
void testOneExecution() {
    // 建一棵 3 节点树
    ExecutableNode root = new InnerGoalNode(SEQUENTIAL, List.of(
        new TaskNode("写一首诗"),
        new TaskNode("翻译成英文"),
        new TaskNode("检查语法")
    ));
    // 执行
    executor.execute(root, ctx).subscribe();
    // 验证三个节点都 COMPLETED
}
```

**这一步跑通后，核心引擎就活了。** 不需要等到全部功能写完才看到效果。

---

## 四、关键决策

| 决策 | 选型 | 原因 |
|---|---|---|
| 构建工具 | Maven（沿用） | 团队成员熟悉 |
| **JDK 版本** | **JDK 25 LTS**（运行时，见 ADR-001） | Compact Headers 内存↓ 10-20%、Scoped Values |
| **Spring Boot 版本** | **4.0.4**（见 ADR-002） | JDK 25 原生支持、autoconfiguration 模块化 |
| DB 迁移 | Flyway（沿用） | 团队已有经验 |
| DB 连接池 | HikariCP（Spring Boot 默认） | 参数见 `18-性能.md` |
| 前端框架 | Vue 3 + TypeScript（沿用） | 纯自定义组件，无 UI 库 |
| 模型客户端 | WebClient（Spring WebFlux） | SSE 流式调用天然适配 |
| 容器化 | Docker Compose | 一键启动开发环境 |

---

## 五、物料清单

| 需要准备的 | 来源 |
|---|---|
| GitHub 仓库 | 新建 `ai-sivan` 组织或仓库 |
| Docker Compose | PostgreSQL 16 + pgvector 0.8.0 |
| LLM API Key | OpenAI / Anthropic / 本地 vLLM 任选一个 |
| Maven 父 POM | 从 v1.0 复制或新建 |

**一个人起步，不需要任何外部依赖。** 不需要等设计评审通过，不需要等团队组建。今天就可以开始写 Phase 0。
