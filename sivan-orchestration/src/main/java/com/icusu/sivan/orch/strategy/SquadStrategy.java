package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.orch.executor.SquadExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Squad 编排策略。通过 {@link SquadExecutionService} 执行 Squad 匹配→创建→执行全流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SquadStrategy implements OrchestrationStrategy {

    private final SquadExecutionService squadExecutionService;

    @Override
    public Intent supportedIntent() {
        return Intent.SQUAD;
    }

    @Override
    public Flux<OrchestrationEvent> execute(OrchestrationContext ctx) {
        return Flux.create(sink ->
                squadExecutionService.handleSquad(ctx.taskDescription(), ctx.accountId(),
                        ctx.historyContext(), ctx.account().projectId(),
                        ctx.conversationId(), sink, ctx.mcpTools(),
                        ctx.fileRootPath(), ctx.archived()));
    }
}
