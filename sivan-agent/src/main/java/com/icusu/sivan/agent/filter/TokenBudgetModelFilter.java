package com.icusu.sivan.agent.filter;

import com.icusu.sivan.core.filter.ModelFilter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelParams;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 预算模型过滤器：检查 maxTokens 是否超限，超限则截断消息列表。
 * <p>
 * 超限时保留 SYSTEM 消息和最近的 USER/ASSISTANT 消息，
 * 丢弃中间的旧消息以降低 token 消耗。
 */
@Slf4j
public class TokenBudgetModelFilter extends ModelFilter {

    /** 默认最大 token 预算。 */
    private final int maxBudget;

    public TokenBudgetModelFilter() {
        this(8192);
    }

    public TokenBudgetModelFilter(int maxBudget) {
        this.maxBudget = maxBudget;
    }

    @Override
    protected Mono<Model.ModelResponse> doFilter(
            Model model, List<Msg> messages, ModelParams params) {
        int limit = params.maxTokens() != null ? params.maxTokens() : maxBudget;

        if (limit <= 0 || messages.size() < 3) {
            return proceed(model, messages, params);
        }

        // 估算 token 数（按每字符 0.25 token 粗略估算）
        int estimated = messages.stream()
                .mapToInt(msg -> msg.text().length() / 4)
                .sum();

        if (estimated <= limit) {
            return proceed(model, messages, params);
        }

        log.info("TokenBudgetModelFilter 截断: estimated={}, limit={}, msgCount={}",
                estimated, limit, messages.size());

        // 截断：保留 SYSTEM（第一条）+ 最近的消息
        List<Msg> truncated = new ArrayList<>();
        truncated.add(messages.getFirst()); // SYSTEM
        truncated.addAll(messages.subList(Math.max(1, messages.size() - 3), messages.size()));

        log.info("TokenBudgetModelFilter 截断完成: {} → {} 条消息", messages.size(), truncated.size());
        return proceed(model, truncated, params);
    }
}
