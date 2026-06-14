package com.icusu.sivan.infra.forest.model;

import com.icusu.sivan.domain.shared.port.LanguageModel;
import com.icusu.sivan.domain.forest.vo.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本追踪器 — 记录 token 消耗和费用。
 * <p>
 * 设计文档第 6 节：
 * <ul>
 *   <li>按模型/账户维度记录 token 消耗</li>
 *   <li>估算费用（美元）</li>
 * </ul>
 * <p>
 * 日志 + 内存累计。Prometheus 指标集成在 sivan-web 层（actuator + micrometer）。
 */
@Component
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    private final AtomicLong totalTokensConsumed = new AtomicLong(0);
    private final AtomicLong totalCostMicroUsd = new AtomicLong(0);

    public CostTracker() {
    }

    public void record(LanguageModel model, TokenUsage usage, UUID accountId, UUID conversationId) {
        String modelId = model.modelId();
        long total = usage.totalTokens();

        double inputCost = usage.inputTokens() * model.capabilities().inputPricePer1k() / 1000.0;
        double outputCost = usage.outputTokens() * model.capabilities().outputPricePer1k() / 1000.0;
        double totalCost = inputCost + outputCost;

        totalTokensConsumed.addAndGet(total);
        long costMicro = (long) (totalCost * 1_000_000);
        totalCostMicroUsd.addAndGet(costMicro);

        if (log.isInfoEnabled()) {
            log.info("[成本] model={} tokens={}(in={}+out={}+think={}) cost=${} total=${} account={}",
                    modelId, total,
                    usage.inputTokens(), usage.outputTokens(), usage.thinkingTokens(),
                    String.format("%.6f", totalCost),
                    String.format("%.6f", totalCostMicroUsd.get() / 1_000_000.0),
                    accountId.toString().substring(0, 8));
        }
    }

    public long totalTokens() { return totalTokensConsumed.get(); }
    public double totalCostUsd() { return totalCostMicroUsd.get() / 1_000_000.0; }
}
