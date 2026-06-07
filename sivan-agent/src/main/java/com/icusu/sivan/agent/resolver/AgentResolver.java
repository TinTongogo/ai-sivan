package com.icusu.sivan.agent.resolver;

import com.icusu.sivan.agent.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentResolver {

    private final RoutingEngine routingEngine;

    public Mono<String> resolve(String taskDescription, UUID accountId, UUID conversationId) {
        return routingEngine.resolve(taskDescription, accountId, conversationId);
    }
}
