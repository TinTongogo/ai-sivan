package com.icusu.sivan.infra.goal.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.domain.goal.Task;
import com.icusu.sivan.infra.goal.entity.GoalEntity;
import com.icusu.sivan.infra.goal.entity.GoalMilestoneEntity;
import com.icusu.sivan.infra.goal.entity.GoalTaskEntity;
import com.icusu.sivan.infra.goal.repository.GoalJpaRepository;
import com.icusu.sivan.infra.goal.repository.GoalMilestoneJpaRepository;
import com.icusu.sivan.infra.goal.repository.GoalTaskJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.time.ZoneOffset;

/**
 * 目标仓储适配器，实现 IGoalRepository。
 * <p>milestones 和 tasks 已从 JSONB 迁移到独立表 goal_milestones / goal_tasks，
 * 通过 GoalMilestoneJpaRepository 和 GoalTaskJpaRepository 读写。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoalRepositoryAdapter implements IGoalRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final GoalJpaRepository jpaRepository;
    private final GoalMilestoneJpaRepository milestoneJpaRepository;
    private final GoalTaskJpaRepository taskJpaRepository;

    @Override
    public Optional<Goal> findById(UUID goalId) {
        return jpaRepository.findById(goalId).map(this::toDomainWithMilestones);
    }

    @Override
    public Optional<Goal> findByIdAndAccount(UUID goalId, UUID accountId) {
        return jpaRepository.findById(goalId)
                .filter(e -> e.getAccountId().equals(accountId))
                .map(this::toDomainWithMilestones);
    }

    @Override
    public List<Goal> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomainWithMilestones).toList();
    }

    @Override
    public Optional<Goal> findByConversationId(UUID conversationId) {
        return jpaRepository.findFirstByConversationIdOrderByCreatedAtDesc(conversationId)
                .map(this::toDomainWithMilestones);
    }

    @Override
    public List<Goal> findAllByAccountAndStatus(UUID accountId, String status) {
        return jpaRepository.findByAccountIdAndStatus(accountId, status).stream()
                .map(this::toDomainWithMilestones).toList();
    }

    @Override
    @Transactional
    public void save(Goal goal) {
        GoalEntity entity = toEntity(goal);
        entity = jpaRepository.save(entity);
        if (goal.getGoalId() == null) {
            goal.setGoalId(entity.getGoalId());
        }
        goal.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        goal.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);

        // 持久化 milestones 和 tasks
        if (goal.getMilestones() != null) {
            saveMilestonesAndTasks(entity.getGoalId(), goal.getMilestones());
        }
    }

    @Override
    @Transactional
    public void update(Goal goal) {
        GoalEntity entity = jpaRepository.findById(goal.getGoalId()).orElse(null);
        if (entity == null) return;

        entity.setTitle(goal.getTitle());
        entity.setDescription(goal.getDescription());
        entity.setSuccessCriteria(goal.getSuccessCriteria());
        entity.setStatus(goal.getStatus() != null ? goal.getStatus().name() : entity.getStatus());
        entity.setAutoMode(goal.getAutoMode() != null ? goal.getAutoMode().name() : entity.getAutoMode());
        entity.setCurrentMilestone(goal.getCurrentMilestone());
        entity.setTotalTasks(goal.getTotalTasks());
        entity.setCompletedTasks(goal.getCompletedTasks());
        entity.setPauseReason(goal.getPauseReason());
        entity.setFileRootPath(goal.getFileRootPath());
        if (goal.getSourceSquadId() != null) entity.setSourceSquadId(goal.getSourceSquadId());
        if (goal.getSourceExecutionId() != null) entity.setSourceExecutionId(goal.getSourceExecutionId());
        entity.setCompletedAt(goal.getCompletedAt() != null ? goal.getCompletedAt().atOffset(ZoneOffset.UTC) : null);
        entity.setPausedAt(goal.getPausedAt() != null ? goal.getPausedAt().atOffset(ZoneOffset.UTC) : null);
        jpaRepository.save(entity);
        goal.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
        goal.setVersion(entity.getVersion());

        // 全量替换 milestones 和 tasks
        if (goal.getMilestones() != null) {
            taskJpaRepository.deleteByGoalId(goal.getGoalId());
            milestoneJpaRepository.deleteByGoalId(goal.getGoalId());
            saveMilestonesAndTasks(goal.getGoalId(), goal.getMilestones());
        }
    }

    @Override
    @Transactional
    public void delete(UUID goalId) {
        taskJpaRepository.deleteByGoalId(goalId);
        milestoneJpaRepository.deleteByGoalId(goalId);
        jpaRepository.deleteById(goalId);
    }

    // ========== 内部方法 ==========

    /**
     * 持久化里程碑及其任务列表。
     */
    private void saveMilestonesAndTasks(UUID goalId, List<Milestone> milestones) {
        for (Milestone ms : milestones) {
            GoalMilestoneEntity msEntity = GoalMilestoneEntity.builder()
                    .goalId(goalId)
                    .name(ms.getName())
                    .description(ms.getDescription())
                    .sortOrder(ms.getOrder())
                    .phaseIndex(ms.getPhaseIndex())
                    .phaseMode(ms.getPhaseMode())
                    .build();
            msEntity = milestoneJpaRepository.save(msEntity);

            // 回写 milestoneId 到领域对象
            if (ms.getMilestoneId() == null) {
                ms.setMilestoneId(msEntity.getMilestoneId());
            }

            if (ms.getTasks() != null) {
                for (Task task : ms.getTasks()) {
                    GoalTaskEntity taskEntity = GoalTaskEntity.builder()
                            .goalId(goalId)
                            .milestoneId(msEntity.getMilestoneId())
                            .sortOrder(task.getOrder())
                            .description(task.getDescription())
                            .completed(task.isCompleted())
                            .status(task.getStatus())
                            .artifactSummary(task.getArtifactSummary())
                            .inputArtifact(task.getInputArtifact())
                            .outputFiles(toJsonString(task.getOutputFiles()))
                            .taskRef(task.getTaskRef())
                            .agentIndex(task.getAgentIndex())
                            .agentName(task.getAgentName())
                            .build();
                    taskEntity = taskJpaRepository.save(taskEntity);

                    // 回写 taskId 到领域对象
                    if (task.getTaskId() == null) {
                        task.setTaskId(taskEntity.getTaskId());
                    }
                }
            }
        }
    }

    /**
     * 从 GoalEntity 转换为领域 Goal，并加载 milestones/tasks。
     */
    private Goal toDomainWithMilestones(GoalEntity entity) {
        List<GoalMilestoneEntity> msEntities = milestoneJpaRepository.findByGoalIdOrderBySortOrder(entity.getGoalId());
        List<GoalTaskEntity> taskEntities = taskJpaRepository.findByGoalIdOrderBySortOrder(entity.getGoalId());

        List<Milestone> milestones = msEntities.stream()
                .map(msEntity -> {
                    List<Task> tasks = taskEntities.stream()
                            .filter(t -> msEntity.getMilestoneId().equals(t.getMilestoneId()))
                            .map(this::toTaskDomain)
                            .toList();
                    return Milestone.builder()
                            .milestoneId(msEntity.getMilestoneId())
                            .goalId(msEntity.getGoalId())
                            .name(msEntity.getName())
                            .description(msEntity.getDescription())
                            .order(msEntity.getSortOrder())
                            .phaseIndex(msEntity.getPhaseIndex() != null ? msEntity.getPhaseIndex() : 0)
                            .phaseMode(msEntity.getPhaseMode())
                            .tasks(tasks)
                            .build();
                })
                .toList();

        return Goal.builder()
                .goalId(entity.getGoalId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .conversationId(entity.getConversationId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .successCriteria(entity.getSuccessCriteria())
                .status(entity.getStatus() != null ? GoalStatus.valueOf(entity.getStatus()) : GoalStatus.PENDING)
                .autoMode(entity.getAutoMode() != null ? AutoMode.valueOf(entity.getAutoMode()) : AutoMode.AUTO)
                .milestones(milestones)
                .currentMilestone(entity.getCurrentMilestone() != null ? entity.getCurrentMilestone() : 0)
                .totalTasks(entity.getTotalTasks() != null ? entity.getTotalTasks() : 0)
                .completedTasks(entity.getCompletedTasks() != null ? entity.getCompletedTasks() : 0)
                .pauseReason(entity.getPauseReason())
                .fileRootPath(entity.getFileRootPath())
                .sourceSquadId(entity.getSourceSquadId())
                .sourceExecutionId(entity.getSourceExecutionId())
                .squadTopologyJson(entity.getSquadTopologyJson())
                .currentPhaseIndex(entity.getCurrentPhaseIndex() != null ? entity.getCurrentPhaseIndex() : 0)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .completedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().toLocalDateTime() : null)
                .pausedAt(entity.getPausedAt() != null ? entity.getPausedAt().toLocalDateTime() : null)
                .version(entity.getVersion())
                .build();
    }

    private GoalEntity toEntity(Goal goal) {
        GoalEntity entity = new GoalEntity();
        entity.setGoalId(goal.getGoalId());
        entity.setAccountId(goal.getAccountId());
        entity.setProjectId(goal.getProjectId());
        entity.setConversationId(goal.getConversationId());
        entity.setTitle(goal.getTitle());
        entity.setDescription(goal.getDescription());
        entity.setSuccessCriteria(goal.getSuccessCriteria());
        entity.setStatus(goal.getStatus() != null ? goal.getStatus().name() : "PENDING");
        entity.setAutoMode(goal.getAutoMode() != null ? goal.getAutoMode().name() : "AUTO");
        entity.setCurrentMilestone(goal.getCurrentMilestone());
        entity.setTotalTasks(goal.getTotalTasks());
        entity.setCompletedTasks(goal.getCompletedTasks());
        entity.setPauseReason(goal.getPauseReason());
        entity.setFileRootPath(goal.getFileRootPath());
        entity.setSourceSquadId(goal.getSourceSquadId());
        entity.setSourceExecutionId(goal.getSourceExecutionId());
        entity.setSquadTopologyJson(goal.getSquadTopologyJson());
        entity.setCurrentPhaseIndex(goal.getCurrentPhaseIndex());
        entity.setCompletedAt(goal.getCompletedAt() != null ? goal.getCompletedAt().atOffset(ZoneOffset.UTC) : null);
        entity.setPausedAt(goal.getPausedAt() != null ? goal.getPausedAt().atOffset(ZoneOffset.UTC) : null);
        entity.setVersion(goal.getVersion());
        return entity;
    }

    private Task toTaskDomain(GoalTaskEntity entity) {
        return Task.builder()
                .taskId(entity.getTaskId())
                .milestoneId(entity.getMilestoneId())
                .order(entity.getSortOrder())
                .description(entity.getDescription())
                .agentIndex(entity.getAgentIndex() != null ? entity.getAgentIndex() : 0)
                .agentName(entity.getAgentName())
                .completed(entity.getCompleted() != null && entity.getCompleted())
                .artifactSummary(entity.getArtifactSummary())
                .taskRef(entity.getTaskRef())
                .status(entity.getStatus())
                .inputArtifact(entity.getInputArtifact())
                .outputFiles(parseStringList(entity.getOutputFiles()))
                .build();
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("outputFiles JSON 反序列化失败", e);
            return Collections.emptyList();
        }
    }

    private String toJsonString(Object obj) {
        if (obj == null) return "[]";
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "[]";
        }
    }
}
