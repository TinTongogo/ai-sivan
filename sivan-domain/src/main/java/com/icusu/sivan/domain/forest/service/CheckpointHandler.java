package com.icusu.sivan.domain.forest.service;

import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * HITL 检查点处理器 — 节点执行前暂停等待人工确认。
 * <p>
 * 当节点 metadata 包含 "hitl": true 时，执行到该节点前暂停，
 * 等待人工 approve/reject 后再继续。
 * <p>
 * {@link #check} 返回 {@link Mono#empty()} 表示无需暂停（节点不要求 HITL）；
 * 返回 {@link PauseRequest} 表示需等待人工决策。
 */
public interface CheckpointHandler {

    /**
     * 检查节点是否需要 HITL 暂停。
     * <p>
     * 如果节点需要审批，emit PAUSE 事件并挂起直到人工决策。
     *
     * @return 空表示无需暂停；{@link PauseRequest#isApproved()} 为 true 表示批准通过
     */
    Mono<PauseRequest> check(ExecutableNode node, ExecutionContext ctx);

    /** 对暂停中的节点做出审批决定。 */
    void approve(String nodeId, String accountId);

    /** 拒绝执行。 */
    void reject(String nodeId, String accountId, String reason);

    /**
     * 检查节点 metadata 是否包含 {@code "hitl": true}。
     * <p>
     * 供 {@code ModeStrategy} 在遍历子节点前调用，决定是否需要暂停审批。
     */
    default boolean isHitlRequired(ExecutableNode node) {
        if (node instanceof ContentNode cn) {
            Map<String, Object> meta = cn.metadata();
            return meta != null && Boolean.TRUE.equals(meta.get("hitl"));
        }
        return false;
    }
}
