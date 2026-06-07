package com.icusu.sivan.orch.scheduler;

import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.orch.executor.OrchestrationEvent;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.domain.goal.Milestone;
import com.icusu.sivan.infra.goal.adapter.GoalArtifactRepositoryAdapter;
import com.icusu.sivan.orch.executor.SquadOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalSchedulerTest {

    @Mock private IGoalRepository goalRepository;
    @Mock private GoalArtifactRepositoryAdapter artifactRepository;
    @Mock private SquadOrchestrator squadOrchestrator;
    @Mock private ArtifactScanner artifactScanner;
    @Mock private ArtifactSummarizer artifactSummarizer;
    @Mock private TransactionTemplate transactionTemplate;

    private GoalScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GoalScheduler(goalRepository, artifactRepository, squadOrchestrator,
                artifactScanner, artifactSummarizer, transactionTemplate);
    }

    @Test
    void start_目标不存在返回空() {
        when(goalRepository.findById(any())).thenReturn(Optional.empty());

        List<OrchestrationEvent> events = scheduler.start(UUID.randomUUID()).collectList().block();
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void start_状态不允许抛出异常() {
        Goal goal = Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .title("已完成目标")
                .status(GoalStatus.COMPLETED)
                .autoMode(AutoMode.AUTO)
                .milestones(List.of())
                .build();
        when(goalRepository.findById(goal.getGoalId())).thenReturn(Optional.of(goal));

        assertThrows(IllegalStateException.class,
                () -> scheduler.start(goal.getGoalId()).blockLast());
    }

    @Test
    void start_PENDING状态正常运行() {
        Goal goal = Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .title("新目标")
                .status(GoalStatus.PENDING)
                .autoMode(AutoMode.AUTO)
                .totalTasks(1)
                .completedTasks(0)
                .fileRootPath("/tmp/test-goal")
                .milestones(List.of(Milestone.builder().name("第一阶段").order(0).build()))
                .build();

        when(goalRepository.findById(goal.getGoalId())).thenReturn(Optional.of(goal));
        // start() 调用 goal.start() → status → ACTIVE
        // findNextReadyTask → null (no real tasks) → 结束

        List<OrchestrationEvent> events = scheduler.start(goal.getGoalId()).collectList().block();
        assertNotNull(events);
        assertTrue(events.size() >= 1);
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
    }

    @Test
    void start_PAUSED状态可恢复() {
        Goal goal = Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .title("暂停目标")
                .status(GoalStatus.PAUSED)
                .autoMode(AutoMode.AUTO)
                .totalTasks(2)
                .completedTasks(0)
                .milestones(List.of(Milestone.builder().name("M1").order(0).build()))
                .build();

        when(goalRepository.findById(goal.getGoalId())).thenReturn(Optional.of(goal));

        List<OrchestrationEvent> events = scheduler.start(goal.getGoalId()).collectList().block();
        assertNotNull(events);
        assertEquals(GoalStatus.ACTIVE, goal.getStatus());
    }
}
