# Sivan v2.0 架构评审报告

> 评审日期：2026-06-05
> 版本：v2.0 终版
> 评审范围：docs/架构/2.0/ 全部 26 份文档

---

## 1. 评审概述

### 1.1 评审方法

| 维度 | 方法 | 覆盖文档 |
|---|---|---|
| 架构设计 | 整体架构评估、模式运用、DDD 合规 | 01, 02, 03, C4 |
| 技术选型 | 技术栈评估、依赖分析 | 01, 02, 05, 09, 18 |
| 可扩展性 | 扩展点完备性、接口隔离 | 全部 |
| 安全性 | 威胁模型、审计完整性 | 05, 07 |
| 性能 | 关键路径分析、瓶颈识别 | 01, 04, 18, arc-09 |
| 产品完整性 | 用户引导、国际化、异常处理 | 03, 06, 08, 13, 15 |

### 1.2 总体评分

| 维度 | 评分 | 说明 |
|---|---|---|
| 架构设计 | 9/10 | 统一树模型正确，DDD 战术模式运用合理 |
| 技术选型 | 8/10 | Spring Reactor + PostgreSQL pgvector 生态成熟，MCP 协议是正确选择 |
| 可扩展性 | 9/10 | Registry + Strategy 覆盖全部扩展方向 |
| 安全性 | 8/10 | 威胁面覆盖完整，密钥管理和提示词注入已补充 |
| 性能 | 7/10 | 1000 节点规模可行，并发安全和 LLM 延迟需实现阶段验证 |
| 产品完整性 | 7/10 | 核心链路完备，引导和国际化后续补齐 |

---

## 2. 架构评审

### 2.1 架构优势

**S1 — 统一树模型（最大优势）**

`ForestNode` 作为所有领域实体的公共抽象，是 v2.0 最正确的架构决策。它让执行引擎、压缩引擎、前端渲染三套遍历共用同一数据结构和同一套 Visitor 遍历算法（ExecuteVisitor / CompressVisitor / RenderVisitor）。行业中不存在用单一树模型统一执行 + 压缩 + 展示三个维度的产品。这是技术壁垒。

**S2 — 扩展点全覆盖**

8 个扩展接口覆盖了架构的所有变化方向：

| 接口 | 变化方向 | 注册方式 |
|---|---|---|
| `ModeStrategy` | Mode 数量 | Spring `@Component` |
| `LeafExecutor` | 叶子类型 | `LeafExecutorRegistry` |
| `FoldStrategy` | 折叠策略 | `FoldStrategyRegistry` |
| `ProgressStrategy` | 进度聚合规则 | `ProgressAggregator` |
| `Policy<T>` | 安全策略 | `SandboxManager.policies` |
| `MemoryConnector` | 知识来源 | `ConnectorRegistry` |
| `CapabilityInferrer` | 模型能力推断 | `ProviderFactory` |
| `DecayStrategy` | 记忆衰减 | `FlashbackEngine` |

新增一个类型 = 新增一个类。零修改现有代码。

**S3 — 领域事件驱动解耦**

跨上下文通信通过 Spring `ApplicationEventPublisher` + `@EventListener` 解耦，不需要消息队列。`ForestExecutor` 不依赖 `ProgressAggregator`、`TemplateService`、`TtsService`，只发事件。事件 record 统一在 `00-共享事件.md` 管理。

**S4 — SUMMARY + STREAM 双交付模式**

行业中所有 AI 产品只有"实时对话"一种模式。Sivan 的 SUMMARY 模式（"做完通知你"）覆盖了语音/手表/后台任务三个快速增长场景。这个差异是产品层面而非技术层面的，但架构设计为此提供了完整支持（`SinkFactory.forSummary()` + `NoopSink`）。

### 2.2 风险识别

| 风险 | 等级 | 描述 | 缓解措施 |
|---|---|---|---|---|
| R01 | 🟡 中 | PARALLEL 模式下进度重算与节点更新并发竞争 | `ProgressHeartbeat` 5s 节流合并重算 |
| R02 | 🟡 中 | `cachedSubtreeTokens` 增量更新在 PARALLEL 模式下的并发安全 | `SafeTokenCache` + `synchronized` 祖先链保护 |
| R03 | 🟡 中 | CONDITIONAL 模式 LLM 决策调用延迟不可控（1000 节点 > 30 分钟） | LRU 缓存（128 条目/10 分钟 TTL）+ 独立超时（5s） |
| R04 | 🟢 低 | Reactor `Flux.concatWith` 链在 1000+ 深度下的 GC 压力 | 深度 > 50 切换 `boundedElastic`，需原型验证 |
| R05 | 🟢 低 | 新用户无执行历史，本能模板反馈闭环空跑 | 预置 5 组通用模板（usageCount=999, successRate=0.92） |
| R06 | 🟢 低 | 前端递归渲染 1000 节点时 DOM 性能 | 折叠子树不生成 DOM + `content-visibility:auto` |

### 2.3 瓶颈分析

| 瓶颈 | 位置 | 触发条件 | 预估影响 |
|---|---|---|---|---|
| CTE 递归查询 | `forest_nodes` 表 | 10000+ 节点 | 15-30ms（10000 节点）可接受 |
| 行锁竞争 | `forest_nodes` | PARALLEL + 频繁 UPDATE | 无冲突（主键级锁） |
| LLM 调用排队 | `ModelRouter` | 多 GoalTree 并发 | 取决于并发树数量和 LLM rate limit |
| MCP 连接数 | `ToolProviderManager` | 每个 TaskNode 调不同 MCP | 少量（按需连接，不预连）|

### 2.4 安全评估

| 威胁面 | 覆盖 | 措施 |
|---|---|---|
| 路径穿越 | ✅ | `FilePolicy`: `normalize()` + `startsWith(root)` |
| 命令注入 | ✅ | `ShellPolicy`: 命令白名单 + 参数校验 |
| SSRF | ✅ | `NetworkPolicy`: URL 白名单 + DNS 校验 |
| 提示词注入 | ✅ | `PatternBasedValidator`: `INJECTION_PATTERNS` 检测 |
| 密钥泄漏 | ✅ | `OutputValidator`: `PatternBasedValidator` 正则扫描 |
| 密钥访问审计 | ✅ | `AuditedSecretStore`: 包装器拦截 `get()` |
| MCP 凭证失效 | ✅ | `PreflightResult.credentialValid` + 探针 |

### 2.5 技术选型评估

| 选型 | 评估 | 替代方案 |
|---|---|---|
| Adjacency List + CTE | ✅ 正确。节点 status 频繁变更，Nested Set 维护成本高，Closure Table 无收益 | Nested Set ❌, Closure Table ❌ |
| Spring Reactor | ✅ 合适。响应式流天然适配 SSE + 递归遍历 | WebFlux + Reactor 是标准选择 |
| pgvector | ✅ 正确。契合 PostgreSQL 统一存储策略 | 独立向量数据库 ❌（增加运维复杂度） |
| MCP 协议 | ✅ 开放标准。避免锁定在任意平台 | 自研协议 ❌  |
| Micrometer + Prometheus | ✅ 标准监控路径，不限 vendor | 自研指标系统 ❌ |
| 纯自定义前端组件 | ⚠️ 开发效率低于使用 Ant Design | 短期效率低，长期一致性高 |

---

## 3. 改进建议

### P0 — 实现前必须完成

| # | 建议 | 对应文档 | 工作量 | ADR |
|---|---|---|---|---|
| P0-1 | 做 1000 节点 Reactor 链 GC 压测原型 | `arc-09` | 2 天 | ADR-027 |
| P0-2 | 补 CONDITIONAL 模式 LLM 延迟压测场景到 `arc-09` | `arc-09` | 0.5 天 | ADR-007 |
| P0-3 | 并发环境下的 `SafeTokenCache` 单元测试 | 01 | 1 天 | ADR-006 |

### P1 — 建议实现阶段补齐

| # | 建议 | 对应文档 | 工作量 | ADR |
|---|---|---|---|---|
| P1-1 | 内置 5 组通用模板代码 | 03 | 1 天 | ADR-011 |
| P1-2 | SSE 协议 `error_detail` 级别接入前端 | 08, 15 | 1 天 | ADR-017 |
| P1-3 | `AuditedSecretStore` 接入主配置 | 05 | 0.5 天 | ADR-010 |

### P2 — 上线前补齐

| # | 建议 | 对应文档 | 工作量 | ADR |
|---|---|---|---|---|
| P2-1 | i18n 中文/英文资源文件初始化 | 00, 06, 15 | 2 天 | ADR-012 |
| P2-2 | `<ForestTree>` 递归渲染原型验证 | 15 | 1 天 | ADR-013 |
| P2-3 | 首次对话 `OnboardingGuide` 组件 | 15 | 1 天 | ADR-017 |

---

## 4. 评审确认清单

### 架构完整性

- [x] 统一树模型覆盖执行/压缩/展示三维度
- [x] 五种编排模式（SEQUENTIAL / PARALLEL / CONDITIONAL / HIERARCHICAL / CONSENSUS）全部设计
- [x] A2A 通信（AgentMessageBus）覆盖 Agent 间交互场景
- [x] Trigger 机制（Cron / Event / Location / Condition）已设计
- [x] 领域事件 7 种全部在 `00-共享事件.md` 集中定义

### 可扩展性

- [x] 新增 Mode → 实现 `ModeStrategy`
- [x] 新增叶子类型 → 实现 `LeafExecutor` + `FoldStrategy`
- [x] 新增工具来源 → 实现 `ToolProvider`
- [x] 新增模型提供商 → 实现 `ModelProviderAdapter` + `CapabilityInferrer`
- [x] 新增记忆策略 → 实现 `DecayStrategy`
- [x] 新增安全策略 → 实现 `Policy<T>`
- [x] 新增知识来源 → 实现 `MemoryConnector`
- [x] 全部扩展点：新增一个类，不修改现有代码

### 安全性

- [x] 外部操作统一经过 `SandboxManager.execute()`
- [x] 文件操作：路径穿越防护 + 跨账号隔离
- [x] Shell 操作：命令白名单 + ProcessSandbox 隔离
- [x] 网络请求：URL 白名单 + DNS 校验
- [x] LLM 输出：`PatternBasedValidator` + `LLMAsJudgeValidator` 双策略
- [x] 提示词注入检测（`INJECTION_PATTERNS`）
- [x] MCP 预检：可用性 + 凭证有效性校验
- [x] 密钥管理：`SecretStore` 接口 + `AuditedSecretStore`
- [x] 审计日志：`traceId` 关联可观测性

### 性能

- [x] 1000 节点内存预估 ≈ 200KB（✅ 安全）
- [x] CTE 1000 节点查询 ≈ 2-5ms（✅ 安全）
- [x] 递归深度保护：500 层阈值 + SKIPPED
- [x] 进度重算：心跳节流 5s 合并
- [x] Token 缓存：`SafeTokenCache` 增量维护
- [x] CONDITIONAL 决策：LRU 缓存 + 5s 独立超时

### DDD 战术模式

- [x] 聚合根：`Forest` / `GoalTreeTemplate` / `Message`
- [x] 限界上下文：执行 / 压缩 / 匹配 / 记忆 / 安全
- [x] 领域事件：`00-共享事件.md` 集中管理
- [x] 仓储：`ForestRepository` / `MessageRepository` / `TemplateRepository`
- [x] 值对象：`Progress` / `ForestEvent` / `Delivery` / `Mode`

### 设计原则

- [x] 零 `switch(nodeType)` — 全部走 Registry
- [x] 零 `if(delivery)` — EventSink 走 Decorator
- [x] 接口隔离 — 4 个 `ForestNode` 子接口
- [x] 枢纽原则 — 不为用户增加负担
- [x] 国际化 — `t(key, args)` + `Accept-Language`
- [x] accountId 显式传递 — 零 ThreadLocal

---

## 5. 评审结论

```
✅ 架构正确性：统一树模型方向确认，8 个扩展点新增不修改
✅ 技术选型：Adjacency List + pgvector + MCP + Reactor 均合理
⚠️ P0-1：实现前必须做 1000 节点压测原型（第 1 周）
⚠️ P0-2：补 CONDITIONAL 模式 LLM 延迟压测场景
⚠️ P0-3：SafeTokenCache 并发单元测试
📌 预估工时：46-49 人天，单人约 8 周
📌 建议实现顺序：原型验证（第 1 周）→ 核心引擎（第 2-3 周）
    → 能力层（第 4-5 周）→ 交互层（第 6-7 周）→ 质量（第 8 周）
```
