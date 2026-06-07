package com.icusu.sivan.core.agent;

import com.icusu.sivan.core.context.ExecutionContext;
import reactor.core.publisher.Flux;

public interface ExecutionStrategy {

    Flux<AgentEvent> execute(Agent agent, ExecutionContext ctx);
}
