package com.icusu.sivan.core.agent;

import com.icusu.sivan.core.context.ExecutionContext;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.tool.SkillProvider;
import com.icusu.sivan.core.tool.ToolProvider;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * 智能体端口。
 *
 * <p>核心依赖：{@link Model} + {@link ToolProvider} + {@link SkillProvider}。
 * 执行行为委派给 {@link ExecutionStrategy}。
 */
public interface Agent {

    String agentId();

    Model model();

    ToolProvider toolProvider();

    SkillProvider skillProvider();

    /** 流式执行，发射 {@link AgentEvent}（Chunk/Thinking/ToolCall/ToolResult/Completed/Error）。 */
    Flux<AgentEvent> execute(ExecutionContext ctx);

    static Builder builder() { return new Builder(); }

    class Builder {
        private String agentId;
        private Model model;
        private ToolProvider toolProvider;
        private SkillProvider skillProvider = SkillProvider.EMPTY;
        private ExecutionStrategy executionStrategy;

        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder languageModel(Model lm) { this.model = lm; return this; }
        public Builder toolProvider(ToolProvider tp) { this.toolProvider = tp; return this; }
        public Builder skillProvider(SkillProvider sp) { this.skillProvider = sp; return this; }
        public Builder executionStrategy(ExecutionStrategy s) { this.executionStrategy = s; return this; }

        public Agent build() {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(toolProvider, "toolProvider");
            Objects.requireNonNull(executionStrategy, "executionStrategy");
            return new DefaultAgent(agentId, model, toolProvider, skillProvider, executionStrategy);
        }
    }
}

record DefaultAgent(
        String agentId,
        Model model,
        ToolProvider toolProvider,
        SkillProvider skillProvider,
        ExecutionStrategy executionStrategy
) implements Agent {

    @Override
    public Flux<AgentEvent> execute(ExecutionContext ctx) {
        return executionStrategy.execute(this, ctx);
    }
}
