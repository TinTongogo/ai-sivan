package com.icusu.sivan.core.pipeline;

import com.icusu.sivan.core.context.ExecutionContext;
import reactor.core.publisher.Mono;

public interface PipelineStep {

    StepType type();

    Mono<PipelineResult> execute(ExecutionContext ctx);

    record PipelineResult(String stepId, boolean success, Object output, String error) {
        public static PipelineResult success(String stepId, Object output) {
            return new PipelineResult(stepId, true, output, null);
        }

        public static PipelineResult failure(String stepId, String error) {
            return new PipelineResult(stepId, false, null, error);
        }
    }
}
