package com.icusu.sivan.domain.goal;

import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Goal 聚合根领域行为测试。
 * <p>测试状态机转换：completeTask 里程碑推进、pause/resume、completedAt 时间戳。</p>
 */
class GoalTest {

    private static Task task(int order, boolean completed) {
        return Task.builder()
                .order(order)
                .taskId(UUID.randomUUID())
                .description("Task " + order)
                .completed(completed)
                .status("pending")
                .build();
    }

    private static Milestone ms(int order, String name, Task... tasks) {
        return Milestone.builder()
                .order(order)
                .name(name)
                .tasks(List.of(tasks))
                .build();
    }

    // ========== 状态转换 ==========

    @Test
    void startShouldTransitionPendingToActive() {
        Goal goal = Goal.builder().status(GoalStatus.PENDING).autoMode(AutoMode.AUTO).build();
        goal.start();
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
    }

    @Test
    void startShouldTransitionPausedToActive() {
        Goal goal = Goal.builder().status(GoalStatus.PAUSED).autoMode(AutoMode.AUTO).build();
        goal.start();
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
    }

    @Test
    void startShouldThrowIfAlreadyActive() {
        Goal goal = Goal.builder().status(GoalStatus.ACTIVE).build();
        assertThrows(IllegalStateException.class, goal::start);
    }

    // ========== completeTask / 里程碑推进 ==========

    @Test
    void completeTaskShouldIncrementCount() {
        Goal goal = Goal.builder()
                .status(GoalStatus.ACTIVE)
                .autoMode(AutoMode.AUTO)
                .totalTasks(3)
                .completedTasks(0)
                .build();
        goal.completeTask(0);
        assertEquals(1, goal.getCompletedTasks());
    }

    @Test
    void completeAllTasksShouldSetCompleted() {
        Goal goal = Goal.builder()
                .status(GoalStatus.ACTIVE)
                .autoMode(AutoMode.AUTO)
                .totalTasks(2)
                .completedTasks(1)
                .build();
        goal.completeTask(1);
        assertEquals(GoalStatus.COMPLETED, goal.getStatus());
        assertNotNull(goal.getCompletedAt());
    }

    @Test
    void completeTaskShouldNotExceedTotal() {
        Goal goal = Goal.builder()
                .status(GoalStatus.ACTIVE)
                .totalTasks(3)
                .completedTasks(3)
                .build();
        goal.completeTask(3);
        assertEquals(3, goal.getCompletedTasks());
    }

    // ========== pause / resume ==========

    @Test
    void pauseShouldSetStatusAndPausedAt() {
        Goal goal = Goal.builder().status(GoalStatus.ACTIVE).build();
        goal.pause("test reason");
        assertEquals(GoalStatus.PAUSED, goal.getStatus());
        assertEquals("test reason", goal.getPauseReason());
        assertNotNull(goal.getPausedAt());
    }

    @Test
    void resumeShouldRestoreActiveAndClearPausedAt() {
        Goal goal = Goal.builder().status(GoalStatus.PAUSED).pausedAt(java.time.LocalDateTime.now()).build();
        goal.resume();
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
        assertNull(goal.getPauseReason());
        assertNull(goal.getPausedAt());
    }

    // ========== countTasksUpToMilestone ==========

    @Test
    void countTasksUpToMilestoneShouldReturnCorrectCount() {
        List<Milestone> milestones = List.of(
                ms(0, "M0", task(0, false), task(1, false)),
                ms(1, "M1", task(2, false), task(3, false), task(4, false))
        );
        Goal goal = Goal.builder()
                .status(GoalStatus.PENDING)
                .milestones(milestones)
                .totalTasks(5)
                .build();

        assertEquals(2, goal.countTasksUpToMilestone(0));
        assertEquals(5, goal.countTasksUpToMilestone(1));
    }

    // ========== findNextReadyTask ==========

    @Test
    void findNextReadyTaskShouldReturnFirstIncomplete() {
        List<Milestone> milestones = List.of(
                ms(0, "M0", task(0, true), task(1, false), task(2, false))
        );
        Goal goal = Goal.builder()
                .status(GoalStatus.ACTIVE)
                .milestones(milestones)
                .totalTasks(3)
                .completedTasks(1)
                .build();

        Task next = goal.findNextReadyTask();
        assertNotNull(next);
        assertEquals(1, next.getOrder());
    }

    @Test
    void findNextReadyTaskShouldReturnNullWhenAllDone() {
        List<Milestone> milestones = List.of(
                ms(0, "M0", task(0, true), task(1, true))
        );
        Goal goal = Goal.builder()
                .status(GoalStatus.ACTIVE)
                .milestones(milestones)
                .totalTasks(2)
                .completedTasks(2)
                .build();

        assertNull(goal.findNextReadyTask());
    }

    // ========== getCurrentMilestoneObj ==========

    @Test
    void getCurrentMilestoneObjShouldReturnCorrectMilestone() {
        List<Milestone> milestones = List.of(
                ms(0, "分析", task(0, false)),
                ms(1, "执行", task(1, false))
        );
        Goal goal = Goal.builder().milestones(milestones).currentMilestone(0).build();
        assertEquals("分析", goal.getCurrentMilestoneObj().getName());

        goal.setCurrentMilestone(1);
        assertEquals("执行", goal.getCurrentMilestoneObj().getName());
    }

    // ========== getAllTasks ==========

    @Test
    void getAllTasksShouldFlattenAllMilestones() {
        List<Milestone> milestones = List.of(
                ms(0, "M0", task(0, false), task(1, false)),
                ms(1, "M1", task(2, false))
        );
        Goal goal = Goal.builder().milestones(milestones).build();
        assertEquals(3, goal.getAllTasks().size());
    }
}
