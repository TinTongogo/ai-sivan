package com.icusu.sivan.web.goal.service;

import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalArtifactRepository;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.orch.scheduler.GoalDecomposer;
import com.icusu.sivan.web.goal.dto.CreateGoalRequest;
import com.icusu.sivan.web.goal.dto.GoalProgressResponse;
import com.icusu.sivan.web.goal.dto.GoalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalServiceTest {

    @Mock private IGoalRepository goalRepository;
    @Mock private IGoalArtifactRepository artifactRepository;
    @Mock private GoalDecomposer goalDecomposer;

    private GoalService goalService;

    @BeforeEach
    void setUp() {
        // GoalService 需要 5 个参数，但我们只 mock 前 3 个，后面 2 个传 null（不会被实际用到）
        goalService = new GoalService(goalRepository, artifactRepository, goalDecomposer, null, null);
    }

    private static Goal aGoal(UUID accountId) {
        return Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(accountId)
                .title("测试目标")
                .description("测试描述")
                .status(GoalStatus.PENDING)
                .autoMode(AutoMode.AUTO)
                .milestones(List.of())
                .build();
    }

    @Test
    void create_成功创建目标() {
        UUID accountId = UUID.randomUUID();
        Goal goal = aGoal(accountId);
        when(goalDecomposer.decompose(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(goal));

        CreateGoalRequest request = new CreateGoalRequest();
        request.setTitle("测试目标");
        request.setDescription("测试描述");

        GoalResponse response = goalService.create(request, accountId);
        assertNotNull(response);
        assertEquals("测试目标", response.getTitle());
        verify(goalRepository).save(goal);
    }

    @Test
    void create_LLM拆解失败抛出异常() {
        when(goalDecomposer.decompose(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.empty());

        CreateGoalRequest request = new CreateGoalRequest();
        request.setTitle("目标");
        request.setDescription("描述");

        assertThrows(RuntimeException.class, () -> goalService.create(request, UUID.randomUUID()));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void create_设置conversationId和autoMode() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Goal goal = aGoal(accountId);
        when(goalDecomposer.decompose(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(goal));

        CreateGoalRequest request = new CreateGoalRequest();
        request.setTitle("t");
        request.setDescription("d");
        request.setConversationId(conversationId);
        request.setAutoMode("CONFIRM_MILESTONE");

        goalService.create(request, accountId);
        assertEquals(conversationId, goal.getConversationId());
        assertEquals(AutoMode.CONFIRM_MILESTONE, goal.getAutoMode());
    }

    @Test
    void create_无效autoMode使用默认() {
        Goal goal = aGoal(UUID.randomUUID());
        when(goalDecomposer.decompose(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(goal));

        CreateGoalRequest request = new CreateGoalRequest();
        request.setTitle("t");
        request.setDescription("d");
        request.setAutoMode("INVALID");

        goalService.create(request, UUID.randomUUID());
        assertEquals(AutoMode.AUTO, goal.getAutoMode());
    }

    @Test
    void getById_返回目标() {
        UUID accountId = UUID.randomUUID();
        Goal goal = aGoal(accountId);
        when(goalRepository.findByIdAndAccount(goal.getGoalId(), accountId))
                .thenReturn(Optional.of(goal));

        GoalResponse response = goalService.getById(goal.getGoalId(), accountId);
        assertNotNull(response);
        assertEquals(goal.getGoalId(), response.getGoalId());
    }

    @Test
    void getById_不存在抛出异常() {
        when(goalRepository.findByIdAndAccount(any(), any())).thenReturn(Optional.empty());
        assertThrows(Exception.class, () -> goalService.getById(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void list_返回所有目标() {
        UUID accountId = UUID.randomUUID();
        when(goalRepository.findAllByAccount(accountId)).thenReturn(List.of(
                aGoal(accountId), aGoal(accountId)));

        var result = goalService.list(accountId);
        assertEquals(2, result.size());
    }

    @Test
    void getProgressByConversation_返回进度() {
        UUID accountId = UUID.randomUUID();
        Goal goal = Goal.builder()
                .goalId(UUID.randomUUID())
                .accountId(accountId)
                .title("目标")
                .status(GoalStatus.ACTIVE)
                .autoMode(AutoMode.AUTO)
                .totalTasks(4)
                .completedTasks(1)
                .milestones(List.of())
                .build();
        UUID conversationId = UUID.randomUUID();
        goal.setConversationId(conversationId);

        when(goalRepository.findByConversationId(conversationId)).thenReturn(Optional.of(goal));
        when(goalRepository.findByIdAndAccount(goal.getGoalId(), accountId))
                .thenReturn(Optional.of(goal));

        GoalProgressResponse progress = goalService.getProgressByConversation(conversationId, accountId);
        assertNotNull(progress);
        assertEquals(4, progress.getTotalTasks());
        assertEquals(1, progress.getCompletedTasks());
    }
}
