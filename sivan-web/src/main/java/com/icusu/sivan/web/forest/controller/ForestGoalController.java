package com.icusu.sivan.web.forest.controller;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.forest.context.Progress;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.tree.InnerGoalNode;
import com.icusu.sivan.domain.forest.tree.TaskNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.domain.forest.port.CheckpointHandler;
import com.icusu.sivan.infra.forest.execution.GoalExecutionService;
import com.icusu.sivan.infra.forest.execution.ProgressAggregator;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Forest Goal 端点 — 创建目标树并执行。
 */
@RestController
@RequestMapping("/api/v2/goals")
public class ForestGoalController {
    private static final Logger log = LoggerFactory.getLogger(ForestGoalController.class);
    private final GoalExecutionService goalExecutionService;
    private final ProgressAggregator progressAggregator;
    private final ForestRepository forestRepository;
    private final CheckpointHandler checkpointHandler;

    public ForestGoalController(GoalExecutionService goalExecutionService,
                                ProgressAggregator progressAggregator,
                                ForestRepository forestRepository,
                                CheckpointHandler checkpointHandler) {
        this.goalExecutionService = goalExecutionService;
        this.progressAggregator = progressAggregator;
        this.forestRepository = forestRepository;
        this.checkpointHandler = checkpointHandler;
    }

    @PostMapping(consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ForestEvent> executeGoal(@RequestBody Map<String, Object> body,
                                          @CurrentAccountId UUID accountId,
                                          @RequestHeader("Last-Event-ID") Optional<String> lastEventId) {
        String title = (String) body.getOrDefault("title", "任务");
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) body.getOrDefault("steps", List.of(title));
        UUID goalId = UUID.randomUUID();

        lastEventId.ifPresent(id -> log.info("[SSE] 重连: goalId={} lastEventId={}", goalId, id));
        List<TreeNode> tasks = steps.stream().map(TaskNode::new).map(t -> (TreeNode) t).toList();
        var root = new InnerGoalNode(Mode.SEQUENTIAL, tasks);

        // 先推一个包含 goalId 的元事件，客户端据此查询进度
        ForestEvent goalEvent = ForestEvent.lifecycle(goalId.toString(), goalId.toString(),
                accountId.toString(), ForestEvent.EventType.LIFECYCLE);

        Forest forest = new Forest(goalId, accountId, null, null, title, root.nodeId());

        return Flux.concat(
                Flux.just(goalEvent),
                goalExecutionService.execute(forest, root, ExecutionContext.create(accountId))
        );
    }

    @PostMapping("/{goalId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelGoal(@PathVariable UUID goalId,
                                                           @CurrentAccountId UUID accountId) {
        boolean cancelled = goalExecutionService.cancelExecution(goalId);
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("goalId", goalId.toString(), "error", "未找到活跃执行"));
        }
        return ResponseEntity.ok(Map.of("goalId", goalId.toString(), "status", "cancelled"));
    }

    @GetMapping("/{goalId}/progress")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable UUID goalId,
                                                            @CurrentAccountId UUID accountId) {
        Forest forest = forestRepository.findForestById(goalId, accountId);
        if (forest == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("goalId", goalId.toString(), "error", "goal not found"));
        }

        TreeNode root = forestRepository.findSubtree(forest.rootNodeId(), accountId);
        if (root == null) {
            return ResponseEntity.ok(Map.of(
                    "goalId", goalId.toString(),
                    "progress", 0,
                    "completed", 0,
                    "activated", 0,
                    "total", 0,
                    "depth", 0
            ));
    }

        Progress p = progressAggregator.aggregate(root);
        double percentage = p.total() > 0 ? (double) p.completed() / p.total() : 0.0;

        return ResponseEntity.ok(Map.of(
                "goalId", goalId.toString(),
                "title", forest.title(),
                "progress", Math.round(percentage * 100.0) / 100.0,
                "completed", p.completed(),
                "activated", p.activated(),
                "total", p.total(),
                "depth", p.depth()
        ));
    }

    // =====================================================================
    // Goal 列表与详情（08-API契约 §3.3）
    // =====================================================================

    /** Goal 列表（按创建时间倒序）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listGoals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentAccountId UUID accountId) {
        List<Forest> forests = forestRepository.listByAccountId(accountId);
        // 简单分页
        int from = page * size;
        int to = Math.min(from + size, forests.size());
        List<Map<String, Object>> items = forests.subList(from, to).stream()
                .map(f -> Map.<String, Object>of(
                        "goalId", f.forestId().toString(),
                        "title", f.title() != null ? f.title() : "",
                        "status", "EXECUTED",
                        "conversationId", f.conversationId() != null ? f.conversationId().toString() : "",
                        "createdAt", f.createdAt() != null ? f.createdAt().toString() : "",
                        "updatedAt", f.updatedAt() != null ? f.updatedAt().toString() : ""
                ))
                .toList();
        return ResponseEntity.ok(Map.of(
                "items", items,
                "total", forests.size(),
                "page", page,
                "size", size
        ));
    }

    /** Goal 详情。 */
    @GetMapping("/{goalId}")
    public ResponseEntity<Map<String, Object>> getGoal(@PathVariable UUID goalId,
                                                        @CurrentAccountId UUID accountId) {
        Forest forest = forestRepository.findForestById(goalId, accountId);
        if (forest == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("goalId", goalId.toString(), "error", "goal not found"));
        }
        return ResponseEntity.ok(Map.of(
                "goalId", forest.forestId().toString(),
                "title", forest.title() != null ? forest.title() : "",
                "conversationId", forest.conversationId() != null ? forest.conversationId().toString() : "",
                "projectId", forest.projectId() != null ? forest.projectId().toString() : "",
                "rootNodeId", forest.rootNodeId(),
                "createdAt", forest.createdAt() != null ? forest.createdAt().toString() : "",
                "updatedAt", forest.updatedAt() != null ? forest.updatedAt().toString() : ""
        ));
    }

    // =====================================================================
    // HITL 审批端点
    // =====================================================================

    /**
     * 批准 HITL 暂停节点继续执行。
     *
     * @param goalId  森林 ID
     * @param body    请求体，包含 nodeId
     * @param accountId 当前账户
     */
    @PostMapping("/{goalId}/hitl/approve")
    public ResponseEntity<Map<String, Object>> approveHitl(
            @PathVariable UUID goalId,
            @RequestBody Map<String, Object> body,
            @CurrentAccountId UUID accountId) {
        String nodeId = (String) body.get("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "nodeId is required"));
        }
        checkpointHandler.approve(nodeId, accountId.toString());
        log.info("[HITL] 审批通过: goalId={} nodeId={}", goalId, nodeId);
        return ResponseEntity.ok(Map.of(
                "goalId", goalId.toString(),
                "nodeId", nodeId,
                "status", "approved"
        ));
    }

    /**
     * 拒绝 HITL 暂停节点执行。
     *
     * @param goalId  森林 ID
     * @param body    请求体，包含 nodeId 和可选的 reason
     * @param accountId 当前账户
     */
    @PostMapping("/{goalId}/hitl/reject")
    public ResponseEntity<Map<String, Object>> rejectHitl(
            @PathVariable UUID goalId,
            @RequestBody Map<String, Object> body,
            @CurrentAccountId UUID accountId) {
        String nodeId = (String) body.get("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "nodeId is required"));
        }
        String reason = (String) body.getOrDefault("reason", "");
        checkpointHandler.reject(nodeId, accountId.toString(), reason);
        log.info("[HITL] 审批拒绝: goalId={} nodeId={} reason={}", goalId, nodeId, reason);
        return ResponseEntity.ok(Map.of(
                "goalId", goalId.toString(),
                "nodeId", nodeId,
                "status", "rejected"
        ));
    }
}
