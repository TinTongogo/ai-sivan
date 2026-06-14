package com.icusu.sivan.infra.forest;

import com.icusu.sivan.domain.forest.vo.AgentMessage;
import com.icusu.sivan.domain.forest.vo.AgentMessage.MessageType;
import com.icusu.sivan.domain.forest.vo.AgentMessageBus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Agent 消息总线实现 — 基于内存的广播通信。
 * <p>
 * 每个 {@code InnerGoal}（里程碑）范围内有一个实例，
 * 经由 {@link ThreadLocal} 传递给叶子执行器。
 */
public class AgentMessageBusImpl implements AgentMessageBus {

    private final Map<String, FluxSink<AgentMessage>> subscribers = new ConcurrentHashMap<>();
    private final List<AgentMessage> history = new CopyOnWriteArrayList<>();

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public void publish(AgentMessage msg) {
        history.add(msg);
        String topic = msg.topic();
        FluxSink<AgentMessage> sink = subscribers.get(topic);
        if (sink != null) {
            sink.next(msg);
        }
    }

    @Override
    public Flux<AgentMessage> subscribe(String topic) {
        return Flux.<AgentMessage>create(sink -> {
            subscribers.put(topic, sink);
            sink.onDispose(() -> subscribers.remove(topic, sink));
            // 补发历史消息
            history.stream()
                    .filter(m -> topic.equals(m.topic()))
                    .forEach(sink::next);
        });
    }

    @Override
    public <T> Mono<T> request(String targetAgentId, String query, Function<String, T> parser) {
        String correlationId = UUID.randomUUID().toString();
        publish(new AgentMessage("local", targetAgentId, correlationId, query, MessageType.REQUEST));

        return subscribe(correlationId)
                .next()
                .map(msg -> parser.apply(msg.content()))
                .timeout(DEFAULT_REQUEST_TIMEOUT);
    }

    @Override
    public List<String> activeTopics() {
        return List.copyOf(subscribers.keySet());
    }

    @Override
    public void reset() {
        subscribers.clear();
        history.clear();
    }
}
