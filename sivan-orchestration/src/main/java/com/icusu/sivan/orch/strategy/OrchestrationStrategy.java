package com.icusu.sivan.orch.strategy;

import com.icusu.sivan.common.context.Account;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 编排策略接口。每种 Intent 对应一种策略实现。
 * 通过策略模式替代 switch-case 分支，支持 OCP 新增意图类型。
 */
public interface OrchestrationStrategy {

    /** 策略支持的意图类型。 */
    Intent supportedIntent();

    /** 执行编排，返回结构化事件流。 */
    Flux<OrchestrationEvent> execute(OrchestrationContext ctx);

    /** 编排上下文。targetAgent 为 @agent 快捷指令显式指定的智能体名。stream 控制 LLM 是否流式输出，默认 true。
     * historyContext 由 ConversationService.continueOrchestration() 通过 buildEpochs() 预构建，
     * Squad 路径直接复用，不重复调用 ContextBuilder。 */
    record OrchestrationContext(
            String taskDescription,
            UUID accountId,
            String historyContext,
            UUID conversationId,
            Account account,
            String targetAgent,
            List<ToolSpec> mcpTools,
            UUID providerId,
            List<Msg> chatMsgs,
            boolean stream,
            String projectHint,
            String fileRootPath,
            boolean archived
    ) {
        public OrchestrationContext {
            targetAgent = targetAgent != null && !targetAgent.isBlank() ? targetAgent : null;
            projectHint = projectHint != null && !projectHint.isBlank() ? projectHint : null;
        }
    }
}
