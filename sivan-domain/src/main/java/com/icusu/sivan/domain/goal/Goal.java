package com.icusu.sivan.domain.goal;

import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 持续自主目标聚合根。
 * <p>milestones 和 tasks 存储在独立表 goal_milestones / goal_tasks 中，
 * 由 GoalRepositoryAdapter 在 findById 时组装加载。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Goal {

    private UUID goalId;
    private UUID accountId;
    private UUID projectId;
    private UUID conversationId;
    private String title;
    private String description;
    private String successCriteria;
    private GoalStatus status;
    private AutoMode autoMode;
    private List<Milestone> milestones;
    private int currentMilestone;
    private int totalTasks;
    private int completedTasks;
    private String pauseReason;
    private String fileRootPath;
    private UUID sourceSquadId;
    private UUID sourceExecutionId;
    private String squadTopologyJson;        // Squad 的 phases JSON 快照，作为执行蓝图
    private int currentPhaseIndex;           // 当前执行的 Phase 索引
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime pausedAt;
    private Long version;

    // ===== 领域行为 =====

    public boolean isActive() { return GoalStatus.ACTIVE.equals(status); }
    public boolean isPaused() { return GoalStatus.PAUSED.equals(status); }
    public boolean isCompleted() { return GoalStatus.COMPLETED.equals(status); }

    /** 校验并启动目标。 */
    public void start() {
        if (status != GoalStatus.PENDING && status != GoalStatus.PAUSED) {
            throw new IllegalStateException("只有 PENDING 或 PAUSED 状态的目标才能启动");
        }
        this.status = GoalStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    /** 标记一个 Task 完成，更新进度。Squad 模式下同时检查 Phase 是否全部完成。 */
    public void completeTask(int taskOrder) {
        this.completedTasks = Math.min(this.completedTasks + 1, this.totalTasks);
        this.updatedAt = LocalDateTime.now();

        // Squad 模式：标记所属 Milestone/Task 为完成
        if (sourceSquadId != null && milestones != null) {
            for (Milestone ms : milestones) {
                if (ms.getTasks() != null) {
                    for (Task t : ms.getTasks()) {
                        if (t.getOrder() == taskOrder) {
                            t.setCompleted(true);
                            break;
                        }
                    }
                }
            }
            // 检查当前 Phase 是否全部完成，完成则推进到下一 Phase
            if (currentMilestone < milestones.size()) {
                Milestone current = milestones.get(currentMilestone);
                if (current.getTasks() != null
                        && current.getTasks().stream().allMatch(Task::isCompleted)) {
                    this.currentPhaseIndex++;
                    this.currentMilestone++;
                }
            }
        }

        // 检查是否所有 Task 完成
        if (this.completedTasks >= this.totalTasks) {
            this.status = GoalStatus.COMPLETED;
            this.completedAt = LocalDateTime.now();
        }
    }

    /** 暂停目标。 */
    public void pause(String reason) {
        this.status = GoalStatus.PAUSED;
        this.pauseReason = reason;
        this.pausedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 恢复已暂停的目标。 */
    public void resume() {
        if (status != GoalStatus.PAUSED) {
            throw new IllegalStateException("只有 PAUSED 状态的目标才能恢复");
        }
        this.status = GoalStatus.ACTIVE;
        this.pauseReason = null;
        this.pausedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** 标记目标执行失败。 */
    public void fail() {
        this.status = GoalStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    /** 取消目标。 */
    public void cancel() {
        this.status = GoalStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    /** 获取当前里程碑（按 currentMilestone 索引）。 */
    public Milestone getCurrentMilestoneObj() {
        if (milestones == null || milestones.isEmpty()) return null;
        int idx = Math.min(currentMilestone, milestones.size() - 1);
        return milestones.get(idx);
    }

    /** 展开所有里程碑中的所有 Task，展平为列表。 */
    public List<Task> getAllTasks() {
        if (milestones == null) return List.of();
        return milestones.stream()
                .flatMap(m -> m.getTasks() != null ? m.getTasks().stream() : java.util.stream.Stream.empty())
                .toList();
    }

    /** 查找第一个未完成的 Task（按顺序）。 */
    public Task findNextReadyTask() {
        int idx = 0;
        for (Milestone m : milestones) {
            if (m.getTasks() != null) {
                for (Task t : m.getTasks()) {
                    if (!t.isCompleted()) return t;
                    idx++;
                }
            }
        }
        return null;
    }

    /** 统计从开始到指定里程碑的累计 Task 数。 */
    public int countTasksUpToMilestone(int milestoneOrder) {
        if (milestones == null) return 0;
        int count = 0;
        for (int i = 0; i <= milestoneOrder && i < milestones.size(); i++) {
            Milestone m = milestones.get(i);
            if (m.getTasks() != null) count += m.getTasks().size();
        }
        return count;
    }
}
