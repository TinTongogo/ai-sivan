package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.service.AgentMessage.MessageType;
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
 * Agent 消息总线 — 每个 {@code InnerGoal}（里程碑）范围内有效。
 * <p>
 * Agent 在 {@code TaskNode} 执行期间通过此总线互相发现和通信。
 * 总线发布于 {@link com.icusu.sivan.infra.forest.execution.ForestExecutor}，
 * 经由 {@link com.icusu.sivan.domain.forest.context.ExecutionContext} 或 {@link ThreadLocal}
 * 传递给叶子执行器。
 * <p>
 * 设计参照 §3.8：
 * <ul>
 *   <li>{@link #publish} — 广播消息到所有订阅了本 topic 的 Agent</li>
 *   <li>{@link #subscribe} — 订阅一个 topic，返回 Flux 流</li>
 *   <li>{@link #request} — 向指定 Agent 发送请求并等待回复</li>
 * </ul>
 */
public class AgentMessageBus {

    private final Map<String, FluxSink<AgentMessage>> subscribers = new ConcurrentHashMap<>();
    private final List<AgentMessage> history = new CopyOnWriteArrayList<>();

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 广播消息到所有订阅了本 topic 的 Agent。
     */
    public void publish(AgentMessage msg) {
        history.add(msg);
        String topic = msg.topic();
        FluxSink<AgentMessage> sink = subscribers.get(topic);
        if (sink != null) {
            sink.next(msg);
        }
    }

    /**
     * 订阅一个 topic。返回 Flux，Agent 在 execute() 中可合并到主 Flux。
     * <p>
     * 订阅时会补发历史消息中匹配 topic 的消息。
     */
    public Flux<AgentMessage> subscribe(String topic) {
        return Flux.<AgentMessage>create(sink -> {
            subscribers.put(topic, sink);
            // 补发历史消息
            history.stream()
                    .filter(m -> topic.equals(m.topic()))
                    .forEach(sink::next);
        });
    }

    /**
     * 向指定 Agent 发送请求并等待回复。
     *
     * @param targetAgentId 目标 Agent ID
     * @param query         请求内容
     * @param parser        从消息内容解析响应类型
     * @param <T>           响应类型
     * @return 解析后的响应
     */
    public <T> Mono<T> request(String targetAgentId, String query, Function<String, T> parser) {
        String correlationId = UUID.randomUUID().toString();
        publish(new AgentMessage("local", targetAgentId, correlationId, query, MessageType.REQUEST));

        return subscribe(correlationId)
                .next()
                .map(msg -> parser.apply(msg.content()))
                .timeout(DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 当前里程碑内已订阅的活跃 topic 列表。
     */
    public List<String> activeTopics() {
        return List.copyOf(subscribers.keySet());
    }

    /**
     * 清空总线的所有订阅和历史消息。
     */
    public void reset() {
        subscribers.clear();
        history.clear();
    }
}
