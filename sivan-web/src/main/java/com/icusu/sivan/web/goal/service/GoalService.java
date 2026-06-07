package com.icusu.sivan.web.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.GoalArtifact;
import com.icusu.sivan.domain.goal.IGoalArtifactRepository;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.domain.goal.Task;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.orch.scheduler.GoalDecomposer;
import com.icusu.sivan.orch.scheduler.GoalScheduler;
import com.icusu.sivan.web.agent.service.GroupService;
import com.icusu.sivan.web.goal.dto.AppendTaskRequest;
import com.icusu.sivan.web.goal.dto.ArtifactResponse;
import com.icusu.sivan.web.goal.dto.CreateGoalRequest;
import com.icusu.sivan.web.goal.dto.GoalProgressResponse;
import com.icusu.sivan.web.goal.dto.GoalResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 目标业务服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IGoalRepository goalRepository;
    private final IGoalArtifactRepository artifactRepository;
    private final GoalDecomposer goalDecomposer;
    private final GoalScheduler goalScheduler;
    private final GroupService groupService;

    /**
     * 创建目标（自动 LLM 拆解为里程碑和 Task）。
     */
    @Transactional
    public GoalResponse create(CreateGoalRequest request, UUID accountId) {
        Goal goal = goalDecomposer.decompose(
                request.getTitle(),
                request.getDescription(),
                accountId,
                request.getProjectId()
        ).block(); // 同步等待 LLM 拆解结果

        if (goal == null) {
            throw new RuntimeException("目标拆解失败");
        }

        goal.setConversationId(request.getConversationId());
        if (request.getAutoMode() != null) {
            try {
                goal.setAutoMode(AutoMode.valueOf(request.getAutoMode()));
            } catch (IllegalArgumentException e) {
                log.warn("无效的 autoMode: {}, 使用默认 AUTO", request.getAutoMode());
                goal.setAutoMode(AutoMode.AUTO);
            }
        }
        goalRepository.save(goal);
        log.info("目标创建成功: goalId={}, title={}, tasks={}",
                goal.getGoalId(), goal.getTitle(), goal.getTotalTasks());

        return toResponse(goal);
    }

    /**
     * 获取目标列表。
     */
    public List<GoalResponse> list(UUID accountId) {
        return goalRepository.findAllByAccount(accountId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 获取目标详情。
     */
    public GoalResponse getById(UUID goalId, UUID accountId) {
        Goal goal = goalRepository.findByIdAndAccount(goalId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("目标不存在"));
        return toResponse(goal);
    }

    /**
     * 按对话 ID 查询目标进度（供前端 PipelineDialog 展示）。
     */
    public GoalProgressResponse getProgressByConversation(UUID conversationId, UUID accountId) {
        Goal goal = goalRepository.findByConversationId(conversationId)
                .orElse(null);
        if (goal == null || !goal.getAccountId().equals(accountId)) {
            return null;
        }

        List<GoalProgressResponse.MilestoneProgress> milestones = null;
        if (goal.getMilestones() != null) {
            milestones = goal.getMilestones().stream()
                    .map(ms -> {
                        int taskCount = ms.getTasks() != null ? ms.getTasks().size() : 0;
                        int completedCount = ms.getTasks() != null
                                ? (int) ms.getTasks().stream().filter(t -> t.isCompleted()).count()
                                : 0;
                        String msStatus;
                        if (ms.getOrder() < goal.getCurrentMilestone()) {
                            msStatus = "completed";
                        } else if (ms.getOrder() == goal.getCurrentMilestone()) {
                            msStatus = "current";
                        } else {
                            msStatus = "pending";
                        }
                        return GoalProgressResponse.MilestoneProgress.builder()
                                .name(ms.getName())
                                .order(ms.getOrder())
                                .status(msStatus)
                                .taskCount(taskCount)
                                .completedTaskCount(completedCount)
                                .build();
                    })
                    .toList();
        }

        return GoalProgressResponse.builder()
                .goalId(goal.getGoalId())
                .title(goal.getTitle())
                .status(goal.getStatus() != null ? goal.getStatus().name() : "PENDING")
                .totalTasks(goal.getTotalTasks())
                .completedTasks(goal.getCompletedTasks())
                .currentMilestone(goal.getCurrentMilestone())
                .milestones(milestones)
                .build();
    }

    /**
     * 启动目标执行，返回 SSE 事件流。
     */
    public Flux<String> startGoal(UUID goalId, UUID accountId) {
        // 验证所有权
        Goal goal = goalRepository.findByIdAndAccount(goalId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("目标不存在"));

        // 自动解析 fileRootPath
        if (goal.getFileRootPath() == null || goal.getFileRootPath().isBlank()) {
            if (goal.getProjectId() != null) {
                try {
                    String rootPath = groupService.getProjectRootPath(accountId, goal.getProjectId());
                    goal.setFileRootPath(rootPath);
                    goalRepository.update(goal);
                    log.info("自动解析 fileRootPath: goalId={}, path={}", goalId, rootPath);
                } catch (Exception e) {
                    log.warn("fileRootPath 自动解析失败: {}", e.getMessage());
                }
            }
        }

        return goalScheduler.start(goalId)
                .map(this::toSseEvent);
    }

    /**
     * 暂停目标。
     */
    @Transactional
    public void pauseGoal(UUID goalId, UUID accountId, String reason) {
        verifyOwnership(goalId, accountId);
        goalScheduler.pause(goalId, reason);
    }

    /**
     * 恢复目标，返回 SSE 事件流。
     */
    public Flux<String> resumeGoal(UUID goalId, UUID accountId) {
        verifyOwnership(goalId, accountId);
        return goalScheduler.resume(goalId)
                .map(this::toSseEvent);
    }

    /**
     * 取消目标。
     */
    @Transactional
    public void cancelGoal(UUID goalId, UUID accountId) {
        verifyOwnership(goalId, accountId);
        goalScheduler.cancel(goalId);
    }

    /**
     * 获取产物列表。
     */
    public List<ArtifactResponse> listArtifacts(UUID goalId, UUID accountId) {
        verifyOwnership(goalId, accountId);
        return artifactRepository.findByGoalId(goalId).stream()
                .map(this::toArtifactResponse)
                .toList();
    }

    /**
     * 读取产物文件内容（含路径穿越防护）。
     */
    public String readArtifactContent(UUID goalId, UUID accountId, String filePath) {
        Goal goal = goalRepository.findByIdAndAccount(goalId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("目标不存在"));
        if (goal.getFileRootPath() == null || goal.getFileRootPath().isBlank()) {
            throw new DomainException("目标未关联文件目录");
        }

        Path root = Paths.get(goal.getFileRootPath()).normalize().toAbsolutePath();
        Path target = root.resolve(filePath).normalize().toAbsolutePath();
        if (!target.startsWith(root)) {
            throw new DomainException("禁止跨目录访问文件");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new DomainException("文件不存在: " + filePath);
        }
        try {
            return Files.readString(target);
        } catch (java.io.IOException e) {
            throw new DomainException("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 追加 Task 到目标。
     */
    @Transactional
    public GoalResponse appendTasks(UUID goalId, UUID accountId, AppendTaskRequest request) {
        Goal goal = goalRepository.findByIdAndAccount(goalId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("目标不存在"));

        if (request.getDescriptions() == null || request.getDescriptions().isEmpty()) {
            throw new DomainException("Task 描述列表不能为空");
        }

        List<Milestone> milestones = goal.getMilestones();
        if (milestones == null || milestones.isEmpty()) {
            milestones = new ArrayList<>();
        }

        // 获取最后一个里程碑，或创建新里程碑
        Milestone lastMs = milestones.isEmpty() ? null : milestones.get(milestones.size() - 1);
        List<Task> newTasks = new ArrayList<>();
        int baseOrder = goal.getTotalTasks();
        for (int i = 0; i < request.getDescriptions().size(); i++) {
            newTasks.add(Task.builder()
                    .order(baseOrder + i)
                    .description(request.getDescriptions().get(i))
                    .completed(false)
                    .taskId(UUID.randomUUID())
                    .status("pending")
                    .build());
        }

        if (lastMs != null) {
            // 追加到最后一个里程碑
            List<Task> existingTasks = new ArrayList<>(lastMs.getTasks() != null ? lastMs.getTasks() : List.of());
            existingTasks.addAll(newTasks);
            lastMs.setTasks(existingTasks);
        } else {
            // 创建默认里程碑
            milestones.add(Milestone.builder()
                    .name("追加任务")
                    .description("追加的任务")
                    .order(milestones.size())
                    .tasks(newTasks)
                    .build());
        }

        goal.setMilestones(milestones);
        goal.setTotalTasks(goal.getTotalTasks() + newTasks.size());
        goalRepository.update(goal);
        log.info("追加 Task: goalId={}, count={}", goalId, newTasks.size());

        return toResponse(goal);
    }

    // ========== 内部方法 ==========

    private void verifyOwnership(UUID goalId, UUID accountId) {
        goalRepository.findByIdAndAccount(goalId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("目标不存在"));
    }

    /** 将 OrchestrationEvent 序列化为 SSE data 行。 */
    private String toSseEvent(OrchestrationEvent event) {
        try {
            Map<String, Object> json = switch (event.getType()) {
                case "step_start", "step_end" -> Map.of(
                        "type", event.getType(),
                        "step", event.getPhase(),
                        "message", event.getMessage() != null ? event.getMessage() : ""
                );
                case "stream" -> Map.of(
                        "type", "response",
                        "content", event.getMessage() != null ? event.getMessage() : ""
                );
                case "stream_thinking" -> Map.of(
                        "type", "thinking",
                        "content", event.getMessage() != null ? event.getMessage() : ""
                );
                case "tool_call" -> Map.of(
                        "type", "tool_call",
                        "tool", event.getMessage(),
                        "inputSummary", event.getMetadata() != null
                                ? event.getMetadata().toString() : ""
                );
                case "tool_result" -> Map.of(
                        "type", "tool_result",
                        "tool", event.getMessage(),
                        "success", event.getMetadata() != null
                                && Boolean.TRUE.equals(event.getMetadata().get("success")),
                        "outputSummary", event.getMetadata() != null
                                ? (String) event.getMetadata().getOrDefault("output", "") : ""
                );
                case "error" -> Map.of(
                        "type", "error",
                        "message", event.getMessage()
                );
                case "final" -> {
                    Map<String, Object> meta = event.getMetadata() != null ? event.getMetadata() : Map.of();
                    Map<String, Object> m = new java.util.HashMap<>(meta);
                    m.put("type", "final");
                    yield m;
                }
                default -> Map.of("type", event.getType(), "message", event.getMessage());
            };

            String data = OBJECT_MAPPER.writeValueAsString(json);
            return "data: " + data + "\n\n";
        } catch (Exception e) {
            log.warn("SSE 序列化失败: {}", e.getMessage());
            return "data: {\"type\":\"error\",\"message\":\"serialization error\"}\n\n";
        }
    }

    private GoalResponse toResponse(Goal goal) {
        return GoalResponse.builder()
                .goalId(goal.getGoalId())
                .projectId(goal.getProjectId())
                .conversationId(goal.getConversationId())
                .title(goal.getTitle())
                .description(goal.getDescription())
                .status(goal.getStatus() != null ? goal.getStatus().name() : "PENDING")
                .autoMode(goal.getAutoMode() != null ? goal.getAutoMode().name() : "AUTO")
                .milestones(goal.getMilestones())
                .currentMilestone(goal.getCurrentMilestone())
                .totalTasks(goal.getTotalTasks())
                .completedTasks(goal.getCompletedTasks())
                .pauseReason(goal.getPauseReason())
                .fileRootPath(goal.getFileRootPath())
                .createdAt(goal.getCreatedAt())
                .updatedAt(goal.getUpdatedAt())
                .completedAt(goal.getCompletedAt())
                .pausedAt(goal.getPausedAt())
                .build();
    }

    private ArtifactResponse toArtifactResponse(GoalArtifact a) {
        return ArtifactResponse.builder()
                .artifactId(a.getArtifactId())
                .filePath(a.getFilePath())
                .fileType(a.getFileType())
                .summary(a.getSummary())
                .fileSize(a.getFileSize())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
