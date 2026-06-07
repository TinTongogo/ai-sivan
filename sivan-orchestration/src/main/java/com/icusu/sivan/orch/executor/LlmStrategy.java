package com.icusu.sivan.orch.executor;

import com.icusu.sivan.domain.shared.vo.TokenContext;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * LLM 调用策略 — PhaseCallbacks 的 LLM 相关职责拆分。
 * 所有方法返回 {@link Mono}，支持全链路响应式组合。
 */
public interface LlmStrategy {

    record LlmResult(String content, String thinking) {}

    Mono<LlmResult> callLlm(String userMessage, TokenContext ctx);

    default Mono<LlmResult> callLlmForAgent(String agentId, String userMessage, TokenContext ctx) {
        return callLlm(userMessage, ctx);
    }

    default Mono<LlmResult> callReactAgent(String agentId, String userMessage, TokenContext ctx) {
        return callLlmForAgent(agentId, userMessage, ctx);
    }

    TokenContext createTokenContext(UUID accountId, UUID executionId);
}
