package com.icusu.sivan.infra.orchestration.adapter;

import com.icusu.sivan.common.enums.ExecutionStatus;
import com.icusu.sivan.domain.orchestration.ISquadExecutionRepository;
import com.icusu.sivan.domain.orchestration.SquadExecution;
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
class SquadExecutionRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ISquadExecutionRepository repository;

    @Test
    void shouldSaveAndFindById() {
        SquadExecution exec = SquadExecution.builder()
                .squadId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .taskDescription("测试执行")
                .status(ExecutionStatus.PENDING)
                .build();
        repository.save(exec);

        assertNotNull(exec.getExecutionId());

        SquadExecution found = repository.findById(exec.getExecutionId()).orElse(null);
        assertNotNull(found);
        assertEquals("测试执行", found.getTaskDescription());
        assertEquals(ExecutionStatus.PENDING, found.getStatus());
    }

    @Test
    void shouldFindBySquadId() {
        UUID squadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            repository.save(SquadExecution.builder()
                    .squadId(squadId).accountId(accountId)
                    .taskDescription("执行" + i).status(ExecutionStatus.PENDING)
                    .build());
        }

        List<SquadExecution> execs = repository.findBySquadId(squadId);
        assertEquals(2, execs.size());
    }

    @Test
    void shouldUpdateCurrentPhase() {
        SquadExecution exec = SquadExecution.builder()
                .squadId(UUID.randomUUID()).accountId(UUID.randomUUID())
                .taskDescription("阶段测试").status(ExecutionStatus.RUNNING)
                .currentPhase(0)
                .build();
        repository.save(exec);

        repository.updateCurrentPhase(exec.getExecutionId(), 3);

        SquadExecution found = repository.findById(exec.getExecutionId()).orElse(null);
        assertNotNull(found);
        assertEquals(3, found.getCurrentPhase());
    }

    @Test
    void shouldDelete() {
        SquadExecution exec = SquadExecution.builder()
                .squadId(UUID.randomUUID()).accountId(UUID.randomUUID())
                .taskDescription("待删除").status(ExecutionStatus.PENDING)
                .build();
        repository.save(exec);
        UUID id = exec.getExecutionId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }

    @Test
    void shouldCountByAccountAndStatus() {
        UUID accountId = UUID.randomUUID();
        repository.save(SquadExecution.builder()
                .squadId(UUID.randomUUID()).accountId(accountId)
                .taskDescription("运行中").status(ExecutionStatus.RUNNING).build());
        repository.save(SquadExecution.builder()
                .squadId(UUID.randomUUID()).accountId(accountId)
                .taskDescription("等待HITL").status(ExecutionStatus.HITL_PENDING).build());

        long running = repository.countByAccountAndStatus(accountId, "RUNNING");
        assertEquals(1, running);
        long hitl = repository.countByAccountAndStatus(accountId, "HITL_PENDING");
        assertEquals(1, hitl);
    }
}
