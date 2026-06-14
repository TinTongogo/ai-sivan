package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.vo.PauseRequest;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 HITL 检查点处理器 — 暂停等待人工审批。
 * <p>
 * 使用 {@link ConcurrentHashMap} 存储挂起的审批信号量，
 * {@link #check} 通过 {@link Mono#create} 挂起，
 * {@link #approve} / {@link #reject} 从外部（如 Web 端点）调用以放行。
 */
@Component
public class DefaultCheckpointHandler implements CheckpointHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultCheckpointHandler.class);

    private final Map<String, MonoSink<PauseRequest>> pendingApprovals = new ConcurrentHashMap<>();
    private final EventSink eventSink;
    private final boolean autoResume;
    private final long hitlTimeoutMs;

    public DefaultCheckpointHandler(EventSink eventSink,
                                    @Value("${sivan.orchestration.hitl.auto-resume:true}") boolean autoResume,
                                    @Value("${sivan.orchestration.hitl.timeout-ms:3600000}") long hitlTimeoutMs) {
        this.eventSink = eventSink;
        this.autoResume = autoResume;
        this.hitlTimeoutMs = hitlTimeoutMs;
    }

    @Override
    public Mono<PauseRequest> check(ExecutableNode node, ExecutionContext ctx) {
        String nodeId = node.nodeId();
        String hint = node instanceof ContentNode cn ? cn.content() : "";
        String truncated = hint.length() > 100 ? hint.substring(0, 100) + "..." : hint;

        ForestEvent pauseEvent = ForestEvent.pause(nodeId, null, ctx.accountId().toString(),
                "等待审批: " + truncated);
        eventSink.emit(pauseEvent);

        log.info("[HITL] 暂停节点 {} 等待审批: {}", nodeId, truncated);

        Mono<PauseRequest> pending = Mono.<PauseRequest>create(sink -> {
            pendingApprovals.put(nodeId, sink);
        });

        if (hitlTimeoutMs > 0) {
            pending = pending.timeout(Duration.ofMillis(hitlTimeoutMs))
                    .onErrorResume(e -> {
                        pendingApprovals.remove(nodeId);
                        if (autoResume) {
                            log.warn("[HITL] 节点 {} 审批超时，自动继续执行", nodeId);
                            return Mono.just(new PauseRequest(nodeId, "超时自动继续", List.of("approve")));
                        }
                        log.error("[HITL] 节点 {} 审批超时，终止执行", nodeId);
                        return Mono.error(new RuntimeException("HITL 审批超时: " + nodeId));
                    });
        }

        return pending;
    }

    @Override
    public void approve(String nodeId, String accountId) {
        MonoSink<PauseRequest> sink = pendingApprovals.remove(nodeId);
        if (sink != null) {
            sink.success(new PauseRequest(nodeId, "人工批准", List.of("approve")));
            eventSink.emit(ForestEvent.hitlResume(nodeId, null, accountId, "人工批准"));
            log.info("[HITL] 节点 {} 已批准", nodeId);
        } else {
            log.warn("[HITL] 节点 {} 不在暂停状态，无法批准", nodeId);
        }
    }

    @Override
    public void reject(String nodeId, String accountId, String reason) {
        MonoSink<PauseRequest> sink = pendingApprovals.remove(nodeId);
        if (sink != null) {
            String msg = reason != null ? reason : "人工拒绝";
            sink.success(new PauseRequest(nodeId, msg, List.of("reject")));
            eventSink.emit(ForestEvent.hitlReject(nodeId, null, accountId, msg));
            log.info("[HITL] 节点 {} 已拒绝: {}", nodeId, reason);
        } else {
            log.warn("[HITL] 节点 {} 不在暂停状态，无法拒绝", nodeId);
        }
    }

    /** 当前挂起的审批数量。 */
    public int pendingCount() {
        return pendingApprovals.size();
    }
}
