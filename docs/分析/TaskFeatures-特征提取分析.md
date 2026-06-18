# TaskFeatures 特征提取分析

## 文件：`sivan-domain/.../memory/TaskFeatures.java`

---

## 一、当前方案分析

### 1.1 特征维度

`TaskFeatures` 定义 5 个枚举维度的固有特征（task-intrinsic features）：

| 维度 | 枚举值 | 提取方式 | 用途 |
|------|--------|---------|------|
| `Complexity` | LEVEL_1~5 | 规则：长度 + 多步骤关键词 | Tier 0 featureHash 输入 |
| `Dependency` | INDEPENDENT/SEQUENTIAL/PARALLEL/CONDITIONAL | 规则：关键词匹配 | 路由系统特征 |
| `InputStructure` | FREE_TEXT/Q_A/CODE/MULTI_MODAL/STRUCTURED_DATA | 规则：模式匹配 | 路由系统特征 |
| `Domain` | CODING/WRITING/ANALYSIS/CREATIVE/RESEARCH/GENERAL | 规则：关键词优先级匹配 | 路由系统特征 |
| `OutputType` | SHORT_TEXT/LONG_TEXT/CODE/JSON/MULTI_MODAL/DECISION | 规则：关键词匹配 | 路由系统特征 |

### 1.2 局限性

```
规则引擎硬编码 → 无法从数据中学习
  ├── 检测精度依赖关键词覆盖率
  ├── 无特征权重 → 所有关键词等权
  ├── 无语义理解 → "写一个Python脚本" vs "用Python编写一个自动化脚本" 特征不同
  └── 无法利用路由反馈信号优化 → 特征提取与路由解耦
```

`toString()` 输出如 `TaskFeatures[complexity=LEVEL_3, dependency=INDEPENDENT, ...]`，经 MD5 后作为 Tier 0 的 featureHash。相同语义但不同表述的任务产生不同哈希，降低了缓存命中率。

---

## 二、推荐算法

### 2.1 特征选择（Feature Selection）

当前 5 个维度共约 30 个枚举值，每个值的判断依赖 5-20 个关键词。关键词的选择可通过统计方法优化：

| 方法 | 适用场景 | 说明 |
|------|---------|------|
| **互信息（Mutual Information）** | 关键词筛选 | 计算每个词与路由结果的互信息，去掉信息量为负的词 |
| **卡方检验（Chi-square）** | 类别特征筛选 | 检验关键词与 Domain/OutputType 的独立性，保留显著相关词 |
| **L1 正则化（Lasso）** | 稀疏特征选择 | 在逻辑回归上训练，L1 惩罚自动将不重要特征权重置零 |

**场景**：当前 `detectDomain()` 中 "写" 同时作为 CODING 和 WRITING 的关键词，导致混淆。互信息可以量化"写"在两种类别中的区分度。

### 2.2 特征重要性排序（Feature Importance）

假设我们有路由反馈数据（`routing_decisions` 表 + `account_beta_params`），可以将特征提取问题转化为分类问题：

| 方法 | 模型 | 输出 | 应用 |
|------|------|------|------|
| **随机森林** | `RandomForestClassifier` | 特征重要性（MDI） | 评估各维度对路由决策的影响程度 |
| **置换重要性** | `PermutationImportance` | 特征重要性（model-agnostic） | 不依赖模型的特征评估 |
| **SHAP** | 任意模型 | 特征贡献值（正/负方向） | 单次任务的维度贡献解释 |

**场景**：分析 `Complexity` 维度中 LEVEL_3 vs LEVEL_4 对路由决策的实际影响——如果两者在路由分布上无显著差异，可合并以减少特征空间。

### 2.3 特征表示学习（Representation Learning）

从规则引擎升级到学习型特征提取：

| 方法 | 输入 | 输出 | 适用维度 |
|------|------|------|---------|
| **Sentence-BERT** | 任务文本 | 768 维语义向量 | Domain、OutputType（需要语义理解） |
| **TF-IDF + SVD（LSA）** | 任务文本 | 100-300 维向量 | 全维度（轻量级） |
| **FastText** | 任务文本 | 子词级向量 | 代码相关的 InputStructure（拼写变体多） |
| **Contrastive Learning** | (任务, Agent) 对 | 任务 embedding | 利用路由反馈作为对比信号 |

#### 推荐：轻量级 Embedding + 规则混合

```
输入文本
  ├── Sentence-BERT Embedding → Domain 分类器（5 类 softmax）
  ├── 规则引擎 → Complexity、Dependency（结构化特征，规则比学习更可靠）
  ├── 统计特征（长度、符号密度、代码块标记）→ InputStructure
  └── TF-IDF + LR → OutputType
        ↓
  特征向量拼接 → 特征哈希
```

理由：
- Domain 和 OutputType 依赖语义理解，适合 embedding
- Complexity 和 Dependency 是结构化特征，规则即可（且可解释）
- InputStructure 是格式特征，统计特征足够

### 2.4 在线学习（Online Learning）

路由反馈是流式到达的（每次执行完成后更新 Beta 参数），适合在线学习：

| 算法 | 特点 | 适用场景 |
|------|------|---------|
| **FTRL（Follow-the-Regularized-Leader）** | 稀疏模型、实时更新 | 特征权重的在线调整 |
| **Bayesian Probit Regression** | 不确定性量化 | 结合 Beta 参数做置信度校准 |
| **Bandit 算法（Contextual Bandit）** | 探索-利用平衡 | 新任务类型的特征探索 |

**场景**：用户的 Domain 分布随时间变化（今天在写代码，明天在写文档）。在线学习自动调整 Domain 分类器的决策边界。

---

## 三、推荐方案：渐进式升级路线

### Phase 1：统计优化（不改变架构，1-2 天）

```
TaskFeatures 升级：
├── 关键词库：基于 routing_decisions 的互信息排序
├── 加权匹配：TF 加权，高频关键词降权
└── 特征稳定性：n-gram 模糊匹配替代完全匹配
```

### Phase 2：混合提取（新增 EmbeddingService 依赖，3-5 天）

```
任务文本
├── 规则引擎 → Complexity, Dependency, InputStructure
├── 轻量 Embedding（FastText/Sentence-BERT）→ Domain, OutputType
└── 特征融合 → featureHash
```

### Phase 3：在线学习（需要反馈数据积累，1-2 周）

```
路由反馈（成功/失败）
  ↓
在线学习器（FTRL）
  ↓
特征权重调整
  ↓
下一轮特征提取
```

---

## 四、关键设计决策

### 为什么不用纯规则？

规则的最大问题不是精度，而是**不可进化**。每次新增一种任务类型（如 AI Agent 编排），就需要手动添加关键词。学习型方法可以自动适应。

### 为什么不用纯 Embedding？

Embedding 无法有效编码结构化特征。`Dependency` 检测（"先做A再做B" → SEQUENTIAL）依赖句法结构和序列词，embedding 模型对此不敏感，规则反而更可靠。

### 特征学习 vs 路由系统的关系

```
特征提取（TaskFeatures）→ 特征哈希 → Tier 0 精确命中 → 路由决策 → 反馈
   ↑                                                            │
   └──────────────────── 在线学习循环 ────────────────────────────┘
```

反馈信号（`account_beta_params` 中的 alpha/beta）可以反向优化特征提取。当前这条路是断开的——特征提取不知道自己的预测在路由中表现如何。
