package com.icusu.sivan.domain.forest.vo;

import com.icusu.sivan.domain.forest.vo.AgentMessage.MessageType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/**
 * Agent 消息总线 — 每个 {@code InnerGoal}（里程碑）范围内有效。
 * <p>
 * Agent 在 {@code TaskNode} 执行期间通过此总线互相发现和通信。
 * <p>
 * 设计参照 §3.8：
 * <ul>
 *   <li>{@link #publish} — 广播消息到所有订阅了本 topic 的 Agent</li>
 *   <li>{@link #subscribe} — 订阅一个 topic，返回 Flux 流</li>
 *   <li>{@link #request} — 向指定 Agent 发送请求并等待回复</li>
 * </ul>
 */
public interface AgentMessageBus {

    /**
     * 广播消息到所有订阅了本 topic 的 Agent。
     */
    void publish(AgentMessage msg);

    /**
     * 订阅一个 topic。返回 Flux，Agent 在 execute() 中可合并到主 Flux。
     * <p>
     * 订阅时会补发历史消息中匹配 topic 的消息。
     */
    Flux<AgentMessage> subscribe(String topic);

    /**
     * 向指定 Agent 发送请求并等待回复。
     *
     * @param targetAgentId 目标 Agent ID
     * @param query         请求内容
     * @param parser        从消息内容解析响应类型
     * @param <T>           响应类型
     * @return 解析后的响应
     */
    <T> Mono<T> request(String targetAgentId, String query, Function<String, T> parser);

    /**
     * 当前里程碑内已订阅的活跃 topic 列表。
     */
    List<String> activeTopics();

    /**
     * 清空总线的所有订阅和历史消息。
     */
    void reset();
}
