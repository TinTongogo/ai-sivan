# ADR-039：LanguageModel 流式与非流式接口分离

> **状态**：✅ 已决策
> **日期**：2026-06-06

## 背景

`LanguageModel.complete()` 默认通过 `Flux.reduce()` 实现，非流式场景下仍分配 Flux 内部队列。某些 provider（如 OpenAI）的非流式接口更高效。

## 决策

`complete()` 默认实现保持 `Flux.reduce()`，但各 Provider 适配器应覆写此方法，调用 provider 的原生非流式 API。非流式调用方（SUMMARY 模式、模板决策）优先调用 `complete()` 而非 `chat()`。

## 理由

非流式接口省去 SSE 解析和 Flux 队列分配的开销。对 SUMMARY 模式等非实时场景，可节省 10-20% 的 LLM 调用延迟。

## 参考文献

- [02-多模型支持与模型路由.md](../10-设计/02-多模型支持与模型路由.md) §2.1
