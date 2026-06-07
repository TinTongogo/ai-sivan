package com.icusu.sivan.orch.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.context.Account;
import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.common.enums.Intent;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.domain.goal.Task;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.infra.goal.adapter.GoalArtifactRepositoryAdapter;
import com.icusu.sivan.orch.executor.SquadOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 目标调度器 — 状态机，驱动 Goal 的 Task 按序自动推进。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoalScheduler {

    private final IGoalRepository goalRepository;
    private final GoalArtifactRepositoryAdapter artifactRepository;
    private final SquadOrchestrator squadOrchestrator;
    private final ArtifactScanner artifactScanner;
    private final ArtifactSummarizer artifactSummarizer;
    private final TransactionTemplate transactionTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Goal 执行总超时（毫秒），超过此时间后自动标记 FAILED。
     * 可通过配置 sivan.goal.execution-timeout-ms 调整，默认 2 小时（7200000ms）。
     */
    @Value("${sivan.goal.execution-timeout-ms:7200000}")
    private long goalTimeoutMs;

    /**
     * 启动目标执行，返回 SSE 事件流。
     * 事件流在目标全部完成、暂停或超时时结束。
     */
    public Flux<OrchestrationEvent> start(UUID goalId) {
        return Mono.fromCallable(() -> goalRepository.findById(goalId))
                .flatMap(Mono::justOrEmpty)
                .flatMapMany(goal -> {
                    if (goal.getStatus() != GoalStatus.PENDING && goal.getStatus() != GoalStatus.PAUSED) {
                        return Flux.error(new IllegalStateException("目标状态不允许启动: " + goal.getStatus()));
                    }
                    goal.start();
                    goalRepository.update(goal);
                    initializeProjectStructure(goal.getFileRootPath());
                    log.info("目标开始执行: goalId={}, title={}, totalTasks={}, timeoutMs={}",
                            goalId, goal.getTitle(), goal.getTotalTasks(), goalTimeoutMs);

                    Instant startedAt = Instant.now();
                    return Flux.concat(
                            Flux.just(OrchestrationEvent.stepStart("goal",
                                    "目标开始：「" + goal.getTitle() + "」, 共 " + goal.getTotalTasks() + " 个任务")),
                            executeTasksRecursively(goal, startedAt),
                            Flux.just(OrchestrationEvent.stepEnd("goal", "目标全部完成")),
                            Flux.just(OrchestrationEvent.complete(Map.of(
                                    "type", "goal",
                                    "goalId", goal.getGoalId().toString(),
                                    "title", goal.getTitle(),
                                    "totalTasks", goal.getTotalTasks()
                            )))
                    );
                });
    }

    /**
     * 检查目标是否超时，超时时标记 FAILED 并返回超时事件。
     */
    private Flux<OrchestrationEvent> checkTimeout(Goal goal, Instant startedAt) {
        long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
        if (goalTimeoutMs > 0 && elapsed > goalTimeoutMs) {
            log.warn("目标执行超时: goalId={}, elapsedMs={}, timeoutMs={}",
                    goal.getGoalId(), elapsed, goalTimeoutMs);
            goal.fail();
            goalRepository.update(goal);
            // 构造包含已完成 Task 摘要的部分结果事件
            String partialSummary = buildPartialResultSummary(goal);
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "goal_timeout");
            meta.put("goalId", goal.getGoalId().toString());
            meta.put("completedTasks", goal.getCompletedTasks());
            meta.put("totalTasks", goal.getTotalTasks());
            meta.put("elapsedMs", elapsed);
            meta.put("timeoutMs", goalTimeoutMs);
            meta.put("partialResult", partialSummary);
            return Flux.concat(
                    Flux.just(OrchestrationEvent.stepEnd("goal_task",
                            "⏰ 目标执行超时（" + (elapsed / 1000) + "s）已完成 "
                                    + goal.getCompletedTasks() + "/" + goal.getTotalTasks() + " 个任务")),
                    Flux.just(OrchestrationEvent.error("目标执行超过最大时间限制，已自动终止（已完成 "
                            + goal.getCompletedTasks() + "/" + goal.getTotalTasks() + " 个任务）")),
                    Flux.just(OrchestrationEvent.complete(meta))
            );
        }
        return Flux.empty();
    }

    /**
     * 构建已完成 Task 的摘要文本。
     */
    private static String buildPartialResultSummary(Goal goal) {
        if (goal.getMilestones() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Milestone ms : goal.getMilestones()) {
            if (ms.getTasks() == null) continue;
            for (Task t : ms.getTasks()) {
                if (t.isCompleted()) {
                    String summary = t.getArtifactSummary();
                    sb.append("- ").append(t.getDescription());
                    if (summary != null && !summary.isBlank()) {
                        sb.append(" → ").append(summary.length() > 100 ? summary.substring(0, 97) + "..." : summary);
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 递归执行 Task 序列。每个 Task 执行完成后自动推进到下一个。
     * 如果 Task 执行中出现 error 事件，标记 Task 失败并终止执行。
     */
    private Flux<OrchestrationEvent> executeTasksRecursively(Goal goal, Instant startedAt) {
        // 超时检查：checkTimeout 超时时会发事件，未超时返回 Flux.empty()
        // 用 switchIfEmpty 替代 equals 比较（Flux.empty().equals() 永远返回 false）
        return checkTimeout(goal, startedAt)
                .switchIfEmpty(Flux.defer(() -> executeNextTask(goal, startedAt)));
    }

    /**
     * 超时检查通过后，执行下一个 Task。
     */
    private Flux<OrchestrationEvent> executeNextTask(Goal goal, Instant startedAt) {

        Task task = goal.findNextReadyTask();
        if (task == null || !goal.isActive()) {
            return Flux.empty();
        }

        Milestone ms = goal.getCurrentMilestoneObj();
        String phaseName = ms != null ? ms.getName() : "执行";
        int currentNum = goal.getCompletedTasks() + 1;
        String taskMsg = currentNum + "/" + goal.getTotalTasks() + " " + phaseName + "：" + task.getDescription();

        AtomicBoolean hadError = new AtomicBoolean(false);

        return Flux.concat(
                Flux.just(OrchestrationEvent.stepStart("goal_task", taskMsg)),
                executeSingleTask(goal, task)
                        .doOnNext(event -> {
                            if ("error".equals(event.getType())) {
                                hadError.set(true);
                            }
                        }),
                Flux.defer(() -> {
                    if (hadError.get()) {
                        log.warn("Task 执行失败: goalId={}, task={}", goal.getGoalId(), task.getDescription());
                        goal.fail();
                        goalRepository.update(goal);
                        return Flux.just(
                                OrchestrationEvent.stepEnd("goal_task",
                                        "❌ " + currentNum + "/" + goal.getTotalTasks() + " 失败：" + task.getDescription()),
                                OrchestrationEvent.error("任务执行失败: " + task.getDescription())
                        );
                    }
                    // 扫描产物 + 更新进度
                    List<OrchestrationEvent.ArtifactInfo> artifacts = artifactScanner.scan(goal, task);
                    // 生成产物摘要
                    if (!artifacts.isEmpty() && goal.getFileRootPath() != null) {
                        try {
                            Map<String, String> summaries = artifactSummarizer.summarize(
                                    goal.getAccountId(), artifacts, goal.getFileRootPath());
                            task.setArtifactSummary(String.join("; ", summaries.values()));
                        } catch (Exception e) {
                            log.warn("产物摘要生成异常: {}", e.getMessage());
                        }
                    }
                    return completeTaskAndPersist(goal, task)
                            .doOnSuccess(v -> {
                                if (!artifacts.isEmpty()) {
                                    log.info("Task 完成，产物 {} 个", artifacts.size());
                                }
                            })
                            .thenMany(Flux.just(OrchestrationEvent.stepEnd("goal_task",
                                    "✅ " + currentNum + "/" + goal.getTotalTasks() + " 完成：" + task.getDescription())));
                }),
                Flux.defer(() -> {
                    if (hadError.get()) return Flux.empty();
                    return decideNext(goal, startedAt);
                })
        );
    }

    /**
     * 决策下一步：根据 Goal 状态和 autoMode 决定继续还是停止。
     * Squad 拓扑模式下，Phase 全部完成时同时推进 currentPhaseIndex 和 currentMilestone。
     */
    private Flux<OrchestrationEvent> decideNext(Goal goal, Instant startedAt) {
        if (!goal.isActive()) {
            return Flux.empty();
        }

        Task next = goal.findNextReadyTask();
        if (next == null) {
            log.info("目标所有 Task 已完成: goalId={}", goal.getGoalId());
            return Flux.empty();
        }

        // 检查是否到达里程碑边界
        boolean atMilestoneBoundary = false;
        if (goal.getMilestones() != null && goal.getCurrentMilestone() < goal.getMilestones().size() - 1) {
            int tasksUpToCurrentMs = goal.countTasksUpToMilestone(goal.getCurrentMilestone());
            atMilestoneBoundary = goal.getCompletedTasks() >= tasksUpToCurrentMs;
        }

        // Squad 拓扑模式：Phase 完成时 currentPhaseIndex 在 completeTask() 中已推进
        if (atMilestoneBoundary) {
            goal.setCurrentMilestone(goal.getCurrentMilestone() + 1);
            if (goal.getSquadTopologyJson() != null) {
                log.info("Phase 完成，推进到下一 Phase: phaseIndex={}, milestone={}",
                        goal.getCurrentPhaseIndex(), goal.getCurrentMilestone());
            }
        }

        // CONFIRM_MILESTONE 模式：里程碑边界暂停等待确认
        if (atMilestoneBoundary && goal.getAutoMode() == AutoMode.CONFIRM_MILESTONE) {
            String msName = goal.getCurrentMilestoneObj() != null ? goal.getCurrentMilestoneObj().getName() : "";
            goal.pause("里程碑「" + msName + "」已完成，等待确认继续执行下一里程碑");
            goalRepository.update(goal);
            return Flux.just(OrchestrationEvent.stepStart("goal_paused",
                    "里程碑「" + msName + "」已完成，等待确认后继续"));
        }

        if (goal.getAutoMode() == AutoMode.CONFIRM_EACH_TASK) {
            // 暂停等待用户确认
            goal.pause("等待用户确认继续执行下一个任务");
            goalRepository.update(goal);
            return Flux.just(OrchestrationEvent.stepStart("goal_paused",
                    "等待用户确认后继续下一个任务"));
        }

        // AUTO 或 CONFIRM_MILESTONE（非边界）：持久化里程碑推进后自动继续
        if (atMilestoneBoundary) {
            goalRepository.update(goal);
        }
        return executeTasksRecursively(goal, startedAt);
    }

    /**
     * 暂停目标。
     */
    public void pause(UUID goalId, String reason) {
        goalRepository.findById(goalId).ifPresent(goal -> {
            goal.pause(reason);
            goalRepository.update(goal);
            log.info("目标已暂停: goalId={}, reason={}", goalId, reason);
        });
    }

    /**
     * 恢复目标，返回 SSE 事件流。
     */
    public Flux<OrchestrationEvent> resume(UUID goalId) {
        return Mono.fromCallable(() -> goalRepository.findById(goalId))
                .flatMap(Mono::justOrEmpty)
                .flatMapMany(goal -> {
                    goal.resume();
                    goalRepository.update(goal);
                    Instant startedAt = Instant.now();
                    return Flux.concat(
                            Flux.just(OrchestrationEvent.stepStart("goal_resumed",
                                    "目标恢复执行：" + goal.getTitle())),
                            checkTimeout(goal, startedAt),
                            executeTasksRecursively(goal, startedAt),
                            Flux.just(OrchestrationEvent.stepEnd("goal", "目标全部完成")),
                            Flux.just(OrchestrationEvent.complete(Map.of(
                                    "type", "goal",
                                    "goalId", goal.getGoalId().toString()
                            )))
                    );
                });
    }

    /**
     * 取消目标。
     */
    public void cancel(UUID goalId) {
        goalRepository.findById(goalId).ifPresent(goal -> {
            goal.cancel();
            goalRepository.update(goal);
            log.info("目标已取消: goalId={}", goalId);
        });
    }

    /**
     * 执行单个 Task — Phase 感知调度。
     * 如果 Goal 有 Squad 拓扑，注入 Phase 结构信息到上下文，按 Phase 内 Agent 分工执行。
     * 否则回退到纯 SINGLE_AGENT 执行。
     */
    private Flux<OrchestrationEvent> executeSingleTask(Goal goal, Task task) {
        var account = new Account(goal.getAccountId(), goal.getProjectId());

        StringBuilder hintBuilder = new StringBuilder();
        hintBuilder.append("## 目标上下文\n").append(goal.getDescription() != null ? goal.getDescription() : "")
                .append("\n\n当前进度: ").append(goal.getCompletedTasks() + 1).append("/").append(goal.getTotalTasks())
                .append("\n").append(buildTaskContext(goal, task));

        // Squad 拓扑模式：注入 Phase 结构信息
        if (goal.getSourceSquadId() != null && goal.getSquadTopologyJson() != null) {
            hintBuilder.append("\n\n## Squad 执行拓扑\n");
            hintBuilder.append("本任务已创建 Goal 蓝图（sourceSquadId=").append(goal.getSourceSquadId())
                    .append("），当前按 Squad 拓扑的 Phase 阶段推进。\n");

            try {
                List<PhaseNode> allPhases = MAPPER.readValue(
                        goal.getSquadTopologyJson(),
                        new TypeReference<List<PhaseNode>>() {});
                Milestone currentMs = goal.getCurrentMilestoneObj();
                int phaseIdx = (currentMs != null) ? currentMs.getPhaseIndex() : goal.getCurrentPhaseIndex();

                if (allPhases != null && phaseIdx >= 0 && phaseIdx < allPhases.size()) {
                    PhaseNode phase = allPhases.get(phaseIdx);
                    String modeStr = phase.getMode() != null ? phase.getMode().name() : "SEQUENTIAL";
                    String agentsInfo = phase.getAgents() != null ? String.join(" → ", phase.getAgents()) : "";
                    hintBuilder.append("- 当前 Phase：").append(phase.getName() != null ? phase.getName() : "阶段 " + phaseIdx)
                            .append("（").append(modeStr).append("）\n");
                    hintBuilder.append("- 智能体分工：").append(agentsInfo).append("\n");
                    hintBuilder.append("- 当前角色：").append(task.getAgentName() != null ? task.getAgentName() : task.getDescription()).append("\n");
                    hintBuilder.append("- 阶段目标：").append(phase.getDescription() != null ? phase.getDescription() : "").append("\n");
                }
            } catch (Exception e) {
                hintBuilder.append("（无法解析拓扑 JSON）\n");
            }
        } else if (goal.getSourceSquadId() != null) {
            hintBuilder.append("\n\n本任务属于 Squad 编排（sourceSquadId=")
                    .append(goal.getSourceSquadId())
                    .append("），请按原 Squad 阶段分工执行。");
        }

        return squadOrchestrator.orchestrateStream(
                Intent.SINGLE_AGENT,
                task.getDescription(),
                goal.getAccountId(),
                null,
                goal.getConversationId(),
                account,
                null,
                null,
                null,
                null,
                true,
                hintBuilder.toString(),
                goal.getFileRootPath(),
                false
        ).filter(event -> !"final".equals(event.getType()));
    }

    /**
     * 构建 Task 上下文 — 注入前置产物信息。
     */
    private String buildTaskContext(Goal goal, Task task) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("## 前置产物\n");

        var allTasks = goal.getAllTasks();
        for (Task t : allTasks) {
            if (t.getOrder() < task.getOrder() && t.getArtifactSummary() != null && !t.getArtifactSummary().isBlank()) {
                ctx.append("- Task ").append(t.getOrder() + 1).append(": ")
                        .append(t.getArtifactSummary()).append("\n");
            }
        }
        ctx.append("\n工作目录：").append(goal.getFileRootPath() != null ? goal.getFileRootPath() : "项目根目录").append("\n");
        ctx.append("产物输出到 output/ 子目录。\n");
        return ctx.toString();
    }

    /**
     * 原子化完成 Task：设置 Task 完成状态、推进 Goal 进度、持久化。
     * 使用 TransactionTemplate 确保 Goal 与 Task 表变更在同一事务中。
     */
    private Mono<Void> completeTaskAndPersist(Goal goal, Task task) {
        return Mono.fromRunnable(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    task.setCompleted(true);
                    goal.completeTask(task.getOrder());
                    goalRepository.update(goal);
                })
        );
    }

    /**
     * 初始化项目目录结构（output/, data/ 等）。
     */
    private void initializeProjectStructure(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) return;
        try {
            Files.createDirectories(Paths.get(rootPath, "output"));
            Files.createDirectories(Paths.get(rootPath, "data"));
            log.info("项目目录结构已初始化: {}", rootPath);
        } catch (java.io.IOException e) {
            log.warn("初始化项目目录结构失败: {}", e.getMessage());
        }
    }
}
