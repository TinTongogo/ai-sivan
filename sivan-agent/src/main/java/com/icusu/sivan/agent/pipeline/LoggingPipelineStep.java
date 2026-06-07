package com.icusu.sivan.agent.pipeline;

import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.pipeline.PipelineStep;
import com.icusu.sivan.core.pipeline.StepType;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * 日志流水线步骤：记录步骤类型和执行耗时，用于追踪 Agent 执行流水线。
 */
@Slf4j
public class LoggingPipelineStep implements PipelineStep {

    private final StepType stepType;

    public LoggingPipelineStep(StepType stepType) {
        this.stepType = stepType;
    }

    public LoggingPipelineStep() {
        this.stepType = StepType.LLM_CALL;
    }

    @Override
    public StepType type() {
        return stepType;
    }

    @Override
    public Mono<PipelineResult> execute(ExecutionContext ctx) {
        Instant start = Instant.now();
        String convId = ctx.conversationId();
        log.info("Pipeline step [{}] 开始: conversationId={}, msgCount={}",
                stepType, convId, ctx.messages().size());

        return Mono.just(PipelineResult.success(stepType.name(), null))
                .doOnSuccess(result -> {
                    Duration elapsed = Duration.between(start, Instant.now());
                    log.info("Pipeline step [{}] 完成: conversationId={}, durationMs={}",
                            stepType, convId, elapsed.toMillis());
                });
    }
}
