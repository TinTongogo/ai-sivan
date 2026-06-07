package com.icusu.sivan.infra.goal.adapter;

import com.icusu.sivan.common.enums.AutoMode;
import com.icusu.sivan.common.enums.GoalStatus;
import com.icusu.sivan.domain.goal.Goal;
import com.icusu.sivan.domain.goal.IGoalRepository;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class GoalRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IGoalRepository repository;

    @Test
    void shouldSaveAndFindById() {
        Goal goal = Goal.builder()
                .accountId(UUID.randomUUID())
                .title("测试目标")
                .description("测试描述")
                .status(GoalStatus.PENDING)
                .autoMode(AutoMode.AUTO)
                .build();
        repository.save(goal);

        assertNotNull(goal.getGoalId());

        Goal found = repository.findById(goal.getGoalId()).orElse(null);
        assertNotNull(found);
        assertEquals("测试目标", found.getTitle());
        assertEquals(GoalStatus.PENDING, found.getStatus());
    }

    @Test
    void shouldFindByIdAndAccount() {
        UUID accountId = UUID.randomUUID();
        Goal goal = Goal.builder()
                .accountId(accountId).title("我的目标")
                .description("测试描述").status(GoalStatus.PENDING).autoMode(AutoMode.AUTO)
                .build();
        repository.save(goal);

        assertTrue(repository.findByIdAndAccount(goal.getGoalId(), accountId).isPresent());
        assertTrue(repository.findByIdAndAccount(goal.getGoalId(), UUID.randomUUID()).isEmpty());
    }

    @Test
    void shouldFindAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository.save(Goal.builder()
                    .accountId(accountId).title("目标" + i)
                    .description("测试描述").status(GoalStatus.PENDING).autoMode(AutoMode.AUTO)
                    .build());
        }

        List<Goal> goals = repository.findAllByAccount(accountId);
        assertEquals(3, goals.size());
    }

    @Test
    void shouldFindByConversationId() {
        UUID convId = UUID.randomUUID();
        Goal goal = Goal.builder()
                .accountId(UUID.randomUUID()).conversationId(convId)
                .title("对话目标").description("测试描述").status(GoalStatus.PENDING).autoMode(AutoMode.AUTO)
                .build();
        repository.save(goal);

        Goal found = repository.findByConversationId(convId).orElse(null);
        assertNotNull(found);
        assertEquals("对话目标", found.getTitle());
    }

    @Test
    void shouldFindAllByAccountAndStatus() {
        UUID accountId = UUID.randomUUID();
        repository.save(Goal.builder().accountId(accountId).title("进行中")
                .description("进行中描述").status(GoalStatus.ACTIVE).autoMode(AutoMode.AUTO).build());
        repository.save(Goal.builder().accountId(accountId).title("已完成")
                .description("已完成描述").status(GoalStatus.COMPLETED).autoMode(AutoMode.AUTO).build());
        repository.save(Goal.builder().accountId(accountId).title("暂停")
                .description("暂停描述").status(GoalStatus.PAUSED).autoMode(AutoMode.AUTO).build());

        List<Goal> active = repository.findAllByAccountAndStatus(accountId, "ACTIVE");
        assertEquals(1, active.size());
        assertEquals("进行中", active.get(0).getTitle());
    }

    @Test
    void shouldUpdateGoal() {
        Goal goal = Goal.builder()
                .accountId(UUID.randomUUID()).title("旧标题")
                .description("旧描述").totalTasks(5)
                .description("测试描述").status(GoalStatus.PENDING).autoMode(AutoMode.AUTO)
                .build();
        repository.save(goal);

        goal.setTitle("新标题");
        goal.setStatus(GoalStatus.ACTIVE);
        goal.setCompletedTasks(2);
        repository.update(goal);

        Goal found = repository.findById(goal.getGoalId()).orElse(null);
        assertNotNull(found);
        assertEquals("新标题", found.getTitle());
        assertEquals(GoalStatus.ACTIVE, found.getStatus());
        assertEquals(2, found.getCompletedTasks());
    }

    @Test
    void shouldDelete() {
        Goal goal = Goal.builder()
                .accountId(UUID.randomUUID()).title("待删除")
                .description("测试描述").status(GoalStatus.PENDING).autoMode(AutoMode.AUTO)
                .build();
        repository.save(goal);
        UUID id = goal.getGoalId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }
}
