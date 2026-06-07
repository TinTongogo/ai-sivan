package com.icusu.sivan.orch.executor;

import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.orch.strategy.OrchestrationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 编排策略分发器。根据 Intent 将请求路由到对应的 OrchestrationStrategy。
 * 策略通过 Spring 自动注入，新增意图无需修改此分发器。
 */
@Slf4j
@Component
public class OrchestrationDispatcher {

    private final Map<Intent, OrchestrationStrategy> strategies;

    public OrchestrationDispatcher(List<OrchestrationStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        OrchestrationStrategy::supportedIntent,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("重复策略注册: intent={}, 保留 {}", existing.supportedIntent(),
                                    ClassUtils.getUserClass(existing).getSimpleName());
                            return existing;
                        }));
        log.info("编排策略分发器初始化: 已注册策略 [{}]",
                strategies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + ClassUtils.getUserClass(e.getValue()).getSimpleName())
                        .collect(Collectors.joining(", ")));
    }

    /**
     * 根据意图分发到对应策略。
     */
    public Flux<OrchestrationEvent> dispatch(Intent intent, OrchestrationStrategy.OrchestrationContext ctx) {
        OrchestrationStrategy strategy = strategies.get(intent);
        if (strategy == null) {
            log.warn("未知意图类型，降级为 CHAT: intent={}", intent);
            strategy = strategies.get(Intent.CHAT);
        }
        log.debug("编排策略分发: intent={} → {}", intent, strategy);
        return strategy.execute(ctx);
    }

    /**
     * 注册额外策略（供 SquadOrchestrator 等组件动态注册）。
     */
    public void register(Intent intent, OrchestrationStrategy strategy) {
        strategies.put(intent, strategy);
        log.info("动态注册编排策略: intent={} → {}", intent, strategy);
    }
}
