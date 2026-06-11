package com.icusu.sivan.infra;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.service.*;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 为 infra 集成测试提供编排执行层 Mock Bean。
 * 编排能力实现在 sivan-agent 模块，infra 测试不需要真实编排。
 */
@TestConfiguration
public class ForestExecutionTestConfig {

    @Bean
    public ModeDispatcher modeDispatcher() {
        return new ModeDispatcher() {
            @Override
            public Flux<ForestEvent> dispatch(ExecutableNode node, ExecutionContext ctx,
                                               int depth, Continuation next) {
                return Flux.empty();
            }
        };
    }

    @Bean
    public CheckpointHandler checkpointHandler() {
        return new CheckpointHandler() {
            @Override
            public Mono<PauseRequest> check(ExecutableNode node, ExecutionContext ctx) {
                return Mono.empty();
            }

            @Override
            public void approve(String nodeId, String accountId) {}

            @Override
            public void reject(String nodeId, String accountId, String reason) {}
        };
    }
}
