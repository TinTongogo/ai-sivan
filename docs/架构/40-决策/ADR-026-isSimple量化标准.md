# ADR-026：TaskProfile.isSimple 量化标准

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：交叉评审 → 系统架构组

---

## 背景

交叉评审发现 **郑-R2 关注点**：`ModelRouter.forTask()` 的 `isSimple` 判断缺少量化标准。实现阶段如果让每个开发者自己判断，结果会全部走复杂模型。

## 决策

在 `TaskProfile` 中增加 `estimatedInputTokens` 字段，`isSimple` 改为可量化判断：

```java
record TaskProfile(
    boolean requiresThinking,
    boolean requiresToolUse,
    boolean requiresVision,
    long estimatedInputTokens,       // 新增：预估输入 Token 数
    String responseLanguage,
    int maxOutputTokens
) {
    /** 是否为简单任务：短文本 + 无工具 + 无视觉 */
    boolean isSimple() {
        return estimatedInputTokens < 1000
            && !requiresToolUse
            && !requiresVision;
    }
}
```

### 量化标准

| 条件 | 简单任务 | 复杂任务 |
|------|---------|---------|
| `estimatedInputTokens` | < 1000 | ≥ 1000 |
| `requiresToolUse` | false | true |
| `requiresVision` | false | true |

### 简单任务模型路由

简单任务优先路由到本地 / 轻量模型（如 Ollama 部署的 llama3.2），复杂任务路由到云端旗舰模型（如 GPT-4o、Claude Opus）。

## 理由

1. 量化标准消除了开发者主观判断带来的偏差。
2. `estimatedInputTokens` 在执行前即可从输入长度估算，无需调用 LLM 预判。
3. 三条规则（token 数 + 工具 + 视觉）覆盖了当前模型分类的主要维度。

## 参考文献

- [review-最终交叉评审报告.md](../20-评审/review-最终交叉评审报告.md) 郑-R2
- [02-多模型支持与模型路由.md](../10-设计/02-多模型支持与模型路由.md) §TaskProfile
