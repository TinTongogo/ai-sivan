package com.icusu.sivan.agent.service;

import com.icusu.sivan.agent.AgentResult;
import com.icusu.sivan.common.enums.TokenSource;
import com.icusu.sivan.core.model.TokenUsage;
import com.icusu.sivan.domain.token.TokenUsageRepository;
import com.icusu.sivan.domain.shared.vo.TokenContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Token 用量记录器。
 * 将 LLM 调用的 Token 用量持久化到数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageRecorder {

    private final TokenUsageRepository tokenUsageRepository;

    /**
     * 保存 Token 使用记录。
     *
     * @param usage     LLM 返回的用量信息
     * @param ctx       调用上下文
     * @param modelName 模型名称
     */
    public void saveUsage(TokenUsage usage, TokenContext ctx, String modelName) {
        if (usage == null || ctx == null) return;
        if (usage.totalTokens() <= 0) return;
        try {
            com.icusu.sivan.domain.token.TokenUsage tokenUsage = com.icusu.sivan.domain.token.TokenUsage.builder()
                    .accountId(ctx.getAccountId())
                    .projectId(ctx.getProjectId())
                    .modelName(modelName != null ? modelName : "")
                    .inputTokens(usage.promptTokens())
                    .outputTokens(usage.completionTokens())
                    .source(ctx.getSource() != null ? ctx.getSource() : TokenSource.CHAT)
                    .agentId(ctx.getAgentId())
                    .conversationId(ctx.getConversationId())
                    .build();
            tokenUsageRepository.save(tokenUsage);
        } catch (Exception e) {
            log.warn("保存 Token 使用记录失败", e);
        }
    }

    /**
     * 保存 Token 使用记录（无需指定模型名）。
     */
    public void saveUsage(TokenUsage usage, TokenContext ctx) {
        saveUsage(usage, ctx, null);
    }


    /**
     * 保存 AgentResult 的 Token 使用记录。
     */
    public void saveUsage(AgentResult result, TokenContext ctx, String modelName) {
        if (result == null || ctx == null) return;
        if (result.totalTokens() <= 0) return;
        try {
            com.icusu.sivan.domain.token.TokenUsage tokenUsage = com.icusu.sivan.domain.token.TokenUsage.builder()
                    .accountId(ctx.getAccountId())
                    .projectId(ctx.getProjectId())
                    .modelName(modelName != null ? modelName : result.modelName())
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .source(ctx.getSource() != null ? ctx.getSource() : TokenSource.CHAT)
                    .agentId(ctx.getAgentId())
                    .conversationId(ctx.getConversationId())
                    .build();
            tokenUsageRepository.save(tokenUsage);
        } catch (Exception e) {
            log.warn("保存 Token 使用记录失败", e);
        }
    }
}
