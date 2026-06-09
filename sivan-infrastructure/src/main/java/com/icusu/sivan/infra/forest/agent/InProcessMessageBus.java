package com.icusu.sivan.infra.forest.agent;

import com.icusu.sivan.domain.forest.service.AgentMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内 Agent 消息总线 — 使用 Reactor Sinks 实现 pub/sub。
 */
public class InProcessMessageBus {

    private final Map<String, Sinks.Many<AgentMessage>> topics = new ConcurrentHashMap<>();
    private final List<String> activeAgents = new CopyOnWriteArrayList<>();

    public void publish(AgentMessage msg) {
        Sinks.Many<AgentMessage> sink = topics.get(msg.topic());
        if (sink != null) {
            sink.tryEmitNext(msg);
        }
    }

    public Flux<AgentMessage> subscribe(String topic) {
        return topics.computeIfAbsent(topic, k ->
                Sinks.many().multicast().directBestEffort()
        ).asFlux();
    }

    public <T> Mono<T> request(String targetAgentId, String query, Class<T> responseType) {
        return Mono.error(new UnsupportedOperationException("request not implemented"));
    }

    public List<String> activeAgents() {
        return List.copyOf(activeAgents);
    }

    public void registerAgent(String agentId) {
        activeAgents.add(agentId);
    }
}
