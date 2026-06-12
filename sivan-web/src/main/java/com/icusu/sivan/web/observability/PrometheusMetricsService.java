package com.icusu.sivan.web.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Prometheus 指标注册与记录（12-可观测性 §3）。
 * <p>
 * 核心业务指标：
 * <ul>
 *   <li>goal.tree.created — 目标树创建数</li>
 *   <li>goal.tree.completed — 目标树完成数（带 success 标签）</li>
 *   <li>goal.node.duration — 节点执行耗时（按 nodeType, mode 分）</li>
 *   <li>goal.node.failed — 节点失败数（按 nodeType 分）</li>
 *   <li>llm.call.duration — LLM 调用耗时</li>
 *   <li>llm.tokens.total — token 消耗总量</li>
 *   <li>mcp.server.healthy — MCP 连接健康状态（Gauge）</li>
 * </ul>
 */
@Component
public class PrometheusMetricsService {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public PrometheusMetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    // ===== Counters =====

    /** 目标树创建。 */
    public void incrementGoalCreated() {
        counter("goal.tree.created").increment();
    }

    /** 目标树完成。 */
    public void incrementGoalCompleted(boolean success) {
        counter("goal.tree.completed", "success", String.valueOf(success)).increment();
    }

    /** 节点失败。 */
    public void incrementNodeFailed(String nodeType) {
        counter("goal.node.failed", "nodeType", nodeType).increment();
    }

    /** LLM token 消耗。 */
    public void addLlmTokens(int tokens) {
        counter("llm.tokens.total").increment(tokens);
    }

    /** LLM 费用（美元）。 */
    public void addLlmCost(double costUsd) {
        counter("llm.cost.usd").increment(costUsd);
    }

    /** 领域事件发布。 */
    public void incrementEventPublished() {
        counter("eventbus.published").increment();
    }

    // ===== Timers =====

    /** 节点执行耗时。 */
    public void recordNodeDuration(String nodeType, String mode, long durationMs) {
        timer("goal.node.duration", "nodeType", nodeType, "mode", mode)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** LLM 调用耗时。 */
    public void recordLlmCallDuration(long durationMs) {
        timer("llm.call.duration").record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** MCP 调用耗时。 */
    public void recordMcpCallDuration(long durationMs) {
        timer("mcp.call.duration").record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 目标树执行耗时。 */
    public void recordGoalExecutionDuration(long durationMs, String mode) {
        timer("goal.tree.execution_duration", "mode", mode)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 目标树总耗时（含 SUMMARY 等待）。 */
    public void recordGoalTotalDuration(long durationMs) {
        timer("goal.tree.total_duration").record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 递归 CTE 查询耗时。 */
    public void recordCteQueryDuration(long durationMs) {
        timer("db.cte.query.duration").record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ===== Gauges =====

    /** 设置 MCP 服务器健康状态。 */
    public void setMcpServerHealthy(String serverId, boolean healthy) {
        registry.gauge("mcp.server.healthy",
                io.micrometer.core.instrument.Tags.of("serverId", serverId),
                healthy ? 1 : 0);
    }

    // ===== 内部 =====

    private Counter counter(String name, String... tags) {
        String key = tags.length > 0 ? name + ":" + String.join(",", tags) : name;
        return counterCache.computeIfAbsent(key, k ->
                Counter.builder(name)
                        .tags(tags)
                        .description(desc(name))
                        .register(registry));
    }

    private Timer timer(String name, String... tags) {
        String key = tags.length > 0 ? name + ":" + String.join(",", tags) : name;
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder(name)
                        .tags(tags)
                        .description(desc(name))
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
    }

    private static String desc(String name) {
        return switch (name) {
            case "goal.tree.created" -> "目标树创建数";
            case "goal.tree.completed" -> "目标树完成数";
            case "goal.tree.execution_duration" -> "目标树实际执行耗时";
            case "goal.tree.total_duration" -> "目标树端到端耗时";
            case "goal.node.duration" -> "节点执行耗时";
            case "goal.node.failed" -> "节点失败数";
            case "llm.call.duration" -> "LLM 调用耗时";
            case "llm.tokens.total" -> "Token 消耗总量";
            case "llm.cost.usd" -> "累计费用（美元）";
            case "mcp.call.duration" -> "MCP 工具调用耗时";
            case "db.cte.query.duration" -> "递归 CTE 查询耗时";
            case "eventbus.published" -> "领域事件发布数";
            default -> name;
        };
    }
}
