package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.common.event.GoalTreeCompleted;
import com.icusu.sivan.common.event.NodeExecutionFailed;
import com.icusu.sivan.common.event.NodeStatusChanged;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.Delivery;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.vo.AgentMessageBus;
import com.icusu.sivan.infra.forest.AgentMessageBusImpl;
import com.icusu.sivan.domain.forest.port.BudgetEnforcer;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.port.LeafExecutor;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.port.ModeDispatcher;
import com.icusu.sivan.domain.forest.vo.Span;
import com.icusu.sivan.domain.forest.vo.SpanContext;
import com.icusu.sivan.domain.shared.port.SpanExporter;
import com.icusu.sivan.domain.forest.tree.CompressibleNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.infra.forest.repository.ForestExecutionLogJpaRepository;
import com.icusu.sivan.infra.forest.sink.ForestMetricsCollector;
import com.icusu.sivan.infra.forest.sink.SinkFactory;
import com.icusu.sivan.infra.routing.RouteFeedbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * 森林执行器 — 统一的递归遍历引擎。
 * <p>
 * 职责：
 * <ol>
 *   <li>递归遍历 ExecutableNode 树</li>
 *   <li>内部节点 → {@link ModeDispatcher} 分派</li>
 *   <li>叶子节点 → {@link LeafExecutorRegistry} 分派</li>
 *   <li>事件输出 → {@link EventSink} 装饰器链</li>
 * </ol>
 */
@Component
public class ForestExecutor {

    private static final Logger log = LoggerFactory.getLogger(ForestExecutor.class);

    /**
     * 每次执行的 EventSink 装饰器链（按 Delivery 模式创建）。
     * 未设置时回退到 {@link #sink} 终端。
     */
    private static final ThreadLocal<EventSink> deliverySink = new ThreadLocal<>();

    /**
     * 每次执行的 {@link AgentMessageBus}（InnerGoal 范围内）。
     * 叶子执行器通过 ThreadLocal 获取总线进行 A2A 通信。
     */
    private static final ThreadLocal<AgentMessageBus> agentMessageBus = new ThreadLocal<>();

    /**
     * 每次执行的启动时间戳，用于 {@link BudgetEnforcer#checkTime} 检查。
     */
    private static final ThreadLocal<Long> executionStartMs = new ThreadLocal<>();

    /** 获取当前执行的 EventSink — 优先使用装饰器链，回退到终端。 */
    private EventSink activeSink() {
        EventSink active = deliverySink.get();
        return active != null ? active : sink;
    }

    /** 每次执行的 Forest ID，用于日志事件关联。 */
    private static final ThreadLocal<String> forestIdHolder = new ThreadLocal<>();

    /** 获取当前执行的 Forest ID。 */
    public static String currentForestId() { return forestIdHolder.get(); }

    /** 获取当前执行的 AgentMessageBus。 */
    public static AgentMessageBus activeBus() {
        AgentMessageBus bus = agentMessageBus.get();
        return bus != null ? bus : new AgentMessageBusImpl();
    }

    private final ModeDispatcher modeDispatcher;
    private final LeafExecutorRegistry leafExecutors;
    private final ForestRepository forestRepository;
    private final EventSink sink;
    private final ApplicationEventPublisher eventPublisher;
    private final BudgetEnforcer budgetEnforcer;
    private final CheckpointHandler checkpointHandler;
    private final ForestExecutionLogJpaRepository executionLogRepository;
    private final ForestMetricsCollector metricsCollector;
    private final SpanExporter spanExporter;
    private final RouteFeedbackHandler routeFeedback;

    /** 每次执行的 SpanContext 根（用于链式追踪）。 */
    private static final ThreadLocal<SpanContext> traceSpanContext = new ThreadLocal<>();

    /** 是否跳过追踪采样（按 mode 分频，12-可观测性 §4.1）。 */
    private static final ThreadLocal<Boolean> tracingSkipped = new ThreadLocal<>();

    /**
     * 目标树执行超时（毫秒）。覆盖 {@link ExecutionContext#timeoutMs()} 的默认值。
     */
    private final long goalTimeoutMs;

    public ForestExecutor(ModeDispatcher modeDispatcher,
                          LeafExecutorRegistry leafExecutors,
                          ForestRepository forestRepository,
                          EventSink sink,
                          ApplicationEventPublisher eventPublisher,
                          BudgetEnforcer budgetEnforcer,
                          CheckpointHandler checkpointHandler,
                          ForestExecutionLogJpaRepository executionLogRepository,
                          ForestMetricsCollector metricsCollector,
                          SpanExporter spanExporter,
                          RouteFeedbackHandler routeFeedback,
                          @Value("${sivan.goal.execution-timeout-ms:7200000}") long goalTimeoutMs) {
        this.modeDispatcher = modeDispatcher;
        this.leafExecutors = leafExecutors;
        this.forestRepository = forestRepository;
        this.sink = sink;
        this.eventPublisher = eventPublisher;
        this.budgetEnforcer = budgetEnforcer;
        this.checkpointHandler = checkpointHandler;
        this.executionLogRepository = executionLogRepository;
        this.metricsCollector = metricsCollector;
        this.spanExporter = spanExporter;
        this.routeFeedback = routeFeedback;
        this.goalTimeoutMs = goalTimeoutMs;
    }

    /**
     * 执行一棵目标树（不含 forestId，不会记录 execution_logs）。
     */
    public Flux<ForestEvent> execute(ExecutableNode root, ExecutionContext ctx) {
        return execute(root, ctx, Delivery.STREAM, null);
    }

    /**
     * 按指定传递模式执行一棵目标树（不含 forestId，不会记录 execution_logs）。
     */
    public Flux<ForestEvent> execute(ExecutableNode root, ExecutionContext ctx, Delivery delivery) {
        return execute(root, ctx, delivery, null);
    }

    /**
     * 按指定传递模式执行一棵目标树。
     * <p>
     * 内部通过 {@link SinkFactory} 创建 EventSink 装饰器链，
     * 将 {@link EventSink#emit} 路由到对应的输出通道（SSE / 静默 + 领域事件）。
     */
    /**
     * 按指定传递模式执行一棵目标树。
     *
     * @param root      可执行的根节点
     * @param ctx       执行上下文
     * @param delivery  传递模式
     * @param forestId  Forest ID，用于日志事件关联（可为 null，但会跳过 execution_logs）
     */
    public Flux<ForestEvent> execute(ExecutableNode root, ExecutionContext ctx, Delivery delivery, String forestId) {
        if (forestId != null) forestIdHolder.set(forestId);
        EventSink decorated = SinkFactory.create(delivery, sink, executionLogRepository, metricsCollector);
        deliverySink.set(decorated);
        agentMessageBus.set(new AgentMessageBusImpl());
        SpanContext rootSpan = SpanContext.root();
        traceSpanContext.set(rootSpan);
        // 采样决策：按根节点 mode 分频（12-可观测性 §4.1）
        boolean sample = shouldSample(root);
        tracingSkipped.set(!sample);
        if (!sample) {
            log.debug("[Trace] 跳过采样: mode={} nodeId={}", root.mode(), root.nodeId());
        }
        return executeWithContext(root, ctx)
                .doFinally(s -> {
                    forestIdHolder.remove();
                    if (sample) {
                        // 导出根 span
                        String status = s == reactor.core.publisher.SignalType.ON_COMPLETE ? "completed" : "failed";
                        spanExporter.export(new Span(
                                rootSpan.traceId(), null, rootSpan.spanId(),
                                root.nodeId(), root.nodeType(),
                                rootSpan.startTime(), System.currentTimeMillis() - rootSpan.startTime().toEpochMilli(),
                                status));
                    }
                    deliverySink.remove();
                    agentMessageBus.remove();
                    executionStartMs.remove();
                    traceSpanContext.remove();
                    tracingSkipped.remove();
                });
    }

    private Flux<ForestEvent> executeWithContext(ExecutableNode root, ExecutionContext ctx) {
        ExecutionContext frozen = ctx.freeze();
        log.info("[执行] 开始 forest: root={} mode={} timeout={}ms",
                root.nodeId(), root.nodeType(), frozen.timeoutMs());
        long effectiveTimeout = Math.max(frozen.timeoutMs(), goalTimeoutMs);
        executionStartMs.set(System.currentTimeMillis());
        return executeNode(root, frozen, 0)
                .doOnComplete(() -> log.info("[执行] 完成 forest: root={}", root.nodeId()))
                .timeout(Duration.ofMillis(effectiveTimeout))
                .onErrorResume(TimeoutException.class, e -> handleTimeout(root))
                .doOnComplete(() -> {
                    if (root.status() == NodeStatus.COMPLETED) {
                        eventPublisher.publishEvent(new GoalTreeCompleted(
                                root.nodeId(), null, ctx.accountId().toString(), null, true, Instant.now()));
                    }
                });
    }

    private Flux<ForestEvent> executeNode(ExecutableNode node, ExecutionContext ctx, int depth) {
        NodeStatus oldStatus = node.status();

        if (ctx.isCancelled()) {
            node.setStatus(NodeStatus.CANCELLED);
            emitStatusChange(node, oldStatus, ctx);
            ForestEvent cancelled = ForestEvent.lifecycleWithStatus(
                    node.nodeId(), forestId(), ctx.accountId().toString(),
                    ForestEvent.EventType.LIFECYCLE, oldStatus, NodeStatus.CANCELLED);
            activeSink().emit(cancelled);
            return Flux.just(cancelled);
        }

        // 预算检查：深度限制
        BudgetEnforcer.BudgetResult depthCheck = budgetEnforcer.checkDepth(depth, 100);
        if (!depthCheck.allowed()) {
            node.setStatus(NodeStatus.FAILED);
            emitStatusChange(node, oldStatus, ctx);
            ForestEvent err = ForestEvent.errorWithStatus(node.nodeId(), forestId(), ctx.accountId().toString(),
                    depthCheck.reason(), oldStatus, NodeStatus.FAILED);
            activeSink().emit(err);
            return Flux.just(err);
        }
        Long startMs = executionStartMs.get();
        if (startMs != null) {
            long elapsed = System.currentTimeMillis() - startMs;
            BudgetEnforcer.BudgetResult timeCheck = budgetEnforcer.checkTime(elapsed, ctx.timeoutMs());
            if (!timeCheck.allowed()) {
                node.setStatus(NodeStatus.FAILED);
                emitStatusChange(node, oldStatus, ctx);
                ForestEvent err = ForestEvent.errorWithStatus(node.nodeId(), forestId(), ctx.accountId().toString(),
                        timeCheck.reason(), oldStatus, NodeStatus.FAILED);
                activeSink().emit(err);
                return Flux.just(err);
            }
        }

        // 预算检查：节点 token 估算
        if (node instanceof CompressibleNode cn) {
            long estimated = cn.estimateSubtreeTokens();
            if (estimated > 0) {
                long maxTokens = ctx.timeoutMs() * 10; // 粗略换算：每秒约 10 token
                BudgetEnforcer.BudgetResult tokenCheck = budgetEnforcer.checkToken(estimated, maxTokens);
                if (!tokenCheck.allowed()) {
                    node.setStatus(NodeStatus.FAILED);
                    emitStatusChange(node, oldStatus, ctx);
                    ForestEvent err = ForestEvent.errorWithStatus(node.nodeId(), forestId(), ctx.accountId().toString(),
                            tokenCheck.reason(), oldStatus, NodeStatus.FAILED);
                    activeSink().emit(err);
                    return Flux.just(err);
                }
            }
        }

        // HITL 检查已下沉到各 ModeStrategy 实现层
        return doExecute(node, ctx, depth, oldStatus);
    }

    /**
     * 核心执行逻辑 — 在取消/预算/HITL 检查通过后执行。
     * 同时记录节点级 Trace Span。
     */
    private Flux<ForestEvent> doExecute(ExecutableNode node, ExecutionContext ctx, int depth, NodeStatus oldStatus) {
        node.setStatus(NodeStatus.RUNNING);
        emitStatusChange(node, oldStatus, ctx);
        ForestEvent running = ForestEvent.lifecycleWithStatus(
                node.nodeId(), forestId(), ctx.accountId().toString(),
                ForestEvent.EventType.LIFECYCLE, oldStatus, NodeStatus.RUNNING);
        activeSink().emit(running);

        // Trace: 创建子 SpanContext，子节点以此 span 为 parent
        boolean traceEnabled = !Boolean.TRUE.equals(tracingSkipped.get());
        SpanContext parentSpan = traceSpanContext.get();
        SpanContext nodeSpanCtx = (parentSpan != null) ? parentSpan.child(node.nodeId()) : SpanContext.root();
        traceSpanContext.set(nodeSpanCtx);
        Instant nodeStart = Instant.now();

        Flux<ForestEvent> result;
        if (node.isLeaf()) {
            result = executeLeaf(node, ctx);
        } else {
            result = modeDispatcher.dispatch(node, ctx, depth, this::executeNode);
        }

        return Flux.concat(
                Flux.just(running),
                result,
                Mono.defer(() -> {
                    NodeStatus finalStatus = node.status();
                    if (finalStatus != NodeStatus.CANCELLED && finalStatus != NodeStatus.FAILED) {
                        finalStatus = NodeStatus.COMPLETED;
                        node.setStatus(NodeStatus.COMPLETED);
                        int dur = (int) Duration.between(nodeStart, Instant.now()).toMillis();
                        int tok = node instanceof CompressibleNode cn
                                ? (int) Math.max(0, cn.estimateSubtreeTokens()) : 0;
                        emitStatusChange(node, NodeStatus.RUNNING, ctx, dur, tok > 0 ? tok : null);
                    }
                    ForestEvent completed = ForestEvent.lifecycleWithStatus(
                            node.nodeId(), forestId(), ctx.accountId().toString(),
                            ForestEvent.EventType.LIFECYCLE, NodeStatus.RUNNING, node.status());
                    activeSink().emit(completed);

                    // Trace: 导出节点 Span（跳过采样时跳过）
                    if (traceEnabled) {
                        Instant nodeEnd = Instant.now();
                        Span nodeSpan = Span.finished(nodeSpanCtx.traceId(), nodeSpanCtx.parentSpanId(), nodeSpanCtx.spanId(),
                                node.nodeId(), node.nodeType(),
                                nodeStart, nodeEnd, finalStatus.name().toLowerCase());
                        spanExporter.export(nodeSpan);
                    }

                    // 路由反馈：更新 Beta 参数 + 写入 embedding（异步，不阻塞）
                    try {
                        String agentName = node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn
                                ? (String) cn.metadata().get("agentName") : null;
                        String taskContent = node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn2
                                ? cn2.content() : null;
                        if (agentName != null && taskContent != null && ctx.accountId() != null) {
                            routeFeedback.onNodeCompleted(ctx.accountId(), agentName, taskContent, true);
                        }
                    } catch (Exception ignored) {}

                    return Mono.just(completed);
                })
        ).onErrorResume(e -> {
            node.setStatus(NodeStatus.FAILED);
            int dur2 = (int) Duration.between(nodeStart, Instant.now()).toMillis();
            int tok2 = node instanceof CompressibleNode cn2
                    ? (int) Math.max(0, cn2.estimateSubtreeTokens()) : 0;
            emitStatusChange(node, NodeStatus.RUNNING, ctx, dur2, tok2 > 0 ? tok2 : null);
            eventPublisher.publishEvent(new NodeExecutionFailed(
                    node.nodeId(), null, e.getMessage(), ctx.accountId().toString(), Instant.now()));
            log.error("[执行] 节点失败: nodeId={} type={}", node.nodeId(), node.nodeType(), e);
            ForestEvent err = ForestEvent.errorWithStatus(node.nodeId(), forestId(), ctx.accountId().toString(),
                    "节点执行失败: " + e.getMessage(), NodeStatus.RUNNING, NodeStatus.FAILED);
            activeSink().emit(err);

            // Trace: 导出失败的 Span（跳过采样时跳过）
            if (traceEnabled) {
                Instant nodeEnd = Instant.now();
                Span nodeSpan = Span.finished(nodeSpanCtx.traceId(), nodeSpanCtx.parentSpanId(), nodeSpanCtx.spanId(),
                        node.nodeId(), node.nodeType(),
                        nodeStart, nodeEnd, "failed");
                spanExporter.export(nodeSpan);
            }

            // 路由反馈（失败）
            try {
                String agentName = node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn
                        ? (String) cn.metadata().get("agentName") : null;
                String taskContent = node instanceof com.icusu.sivan.domain.forest.tree.ContentNode cn2
                        ? cn2.content() : null;
                if (agentName != null && taskContent != null && ctx.accountId() != null) {
                    routeFeedback.onNodeCompleted(ctx.accountId(), agentName, taskContent, false);
                }
            } catch (Exception ignored) {}

            return Flux.just(err);
        });
    }

    /** 当前 Forest ID，用于事件关联（可能为 null）。 */
    private String forestId() { return forestIdHolder.get(); }

    /**
     * 采样决策（12-可观测性 §4.1）。
     * SUMMARY/CONDITIONAL/CONSENSUS 100%、PARALLEL 10%、SEQUENTIAL/HIERARCHICAL 50%。
     */
    private static boolean shouldSample(ExecutableNode root) {
        if (root == null) return true;
        Mode m = root.mode();
        if (m == null) return true;
        return switch (m) {
            case CONDITIONAL, CONSENSUS -> true;
            case PARALLEL -> root.nodeId().hashCode() % 10 == 0;
            case SEQUENTIAL, HIERARCHICAL -> root.nodeId().hashCode() % 2 == 0;
            default -> true;
        };
    }

    // =====================================================================
    // HITL — 已下沉到各 ModeStrategy 实现层 (CheckpointHandler.isHitlRequired)
    // =====================================================================

    private Flux<ForestEvent> executeLeaf(ExecutableNode node, ExecutionContext ctx) {
        String nodeType = node.nodeType();
        LeafExecutor executor = leafExecutors.forType(nodeType);
        if (executor == null) {
            log.warn("[执行] 无匹配的叶子执行器: nodeType={}", nodeType);
            return Flux.empty();
        }

        Flux<ForestEvent> attempt = executor.execute(node, ctx, activeSink());

        for (int i = 0; i < executor.maxRetries(); i++) {
            final int retryCount = i;
            attempt = attempt.onErrorResume(e -> {
                log.warn("叶子执行失败，重试 {}/{}: {}", retryCount + 1, executor.maxRetries(), e.getMessage());
                return executor.execute(node, ctx, activeSink());
            });
        }
        return attempt;
    }

    private void emitStatusChange(ExecutableNode node, NodeStatus oldStatus, ExecutionContext ctx) {
        emitStatusChange(node, oldStatus, ctx, null, null);
    }

    private void emitStatusChange(ExecutableNode node, NodeStatus oldStatus, ExecutionContext ctx,
                                   Integer durationMs, Integer totalTokens) {
        try {
            eventPublisher.publishEvent(new NodeStatusChanged(
                    node.nodeId(), oldStatus, node.status(), null,
                    ctx.accountId().toString(), Instant.now(), durationMs, totalTokens));
        } catch (Exception e) {
            log.warn("事件发布失败: {}", e.getMessage());
        }
        // 兜底持久化：确保状态入库，不依赖 @EventListener
        if (durationMs != null || totalTokens != null) {
            forestRepository.updateNodeDetails(node.nodeId(), node.status(), ctx.accountId(),
                    durationMs, totalTokens);
        } else {
            forestRepository.updateNodeStatus(node.nodeId(), node.status(), ctx.accountId());
        }
    }

    private Flux<ForestEvent> handleTimeout(ExecutableNode root) {
        root.setStatus(NodeStatus.FAILED);
        activeSink().emit(ForestEvent.error(root.nodeId(), null, null, "GoalTree 执行超时"));
        return Flux.empty();
    }
}
