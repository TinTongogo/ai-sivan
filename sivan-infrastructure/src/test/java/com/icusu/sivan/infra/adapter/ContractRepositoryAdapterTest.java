package com.icusu.sivan.infra.orchestration.adapter;

import com.icusu.sivan.domain.orchestration.Contract;
import com.icusu.sivan.domain.orchestration.IContractRepository;
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
class ContractRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IContractRepository repository;

    @Test
    void shouldSaveAndFindById() {
        UUID accountId = UUID.randomUUID();
        Contract contract = Contract.builder()
                .accountId(accountId)
                .executionId(UUID.randomUUID())
                .phase(0)
                .sourceAgent("agent1")
                .content("契约内容")
                .contentType("text")
                .build();
        repository.save(contract);
        assertNotNull(contract.getContractId());

        Contract found = repository.findById(contract.getContractId()).orElse(null);
        assertNotNull(found);
        assertEquals("契约内容", found.getContent());
        assertEquals("agent1", found.getSourceAgent());
    }

    @Test
    void shouldFindByExecutionId() {
        UUID execId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        repository.save(Contract.builder().accountId(accountId).executionId(execId).phase(0)
                .sourceAgent("a1").content("c1").contentType("text").build());
        repository.save(Contract.builder().accountId(accountId).executionId(execId).phase(1)
                .sourceAgent("a2").content("c2").contentType("text").build());

        List<Contract> contracts = repository.findByExecutionId(execId);
        assertEquals(2, contracts.size());
    }

    @Test
    void shouldFindByExecutionIdAndPhase() {
        UUID execId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        repository.save(Contract.builder().accountId(accountId).executionId(execId).phase(0)
                .sourceAgent("a1").content("阶段0").contentType("text").build());
        repository.save(Contract.builder().accountId(accountId).executionId(execId).phase(1)
                .sourceAgent("a2").content("阶段1").contentType("text").build());

        List<Contract> phase0 = repository.findByExecutionIdAndPhase(execId, 0);
        assertEquals(1, phase0.size());
        assertEquals("阶段0", phase0.get(0).getContent());
    }

    @Test
    void shouldUpdateContent() {
        UUID accountId = UUID.randomUUID();
        Contract contract = Contract.builder()
                .accountId(accountId)
                .executionId(UUID.randomUUID()).phase(0)
                .sourceAgent("a1").content("旧内容").contentType("text")
                .build();
        repository.save(contract);

        repository.updateContent(contract.getContractId(), "新内容");
        Contract found = repository.findById(contract.getContractId()).orElse(null);
        assertNotNull(found);
        assertEquals("新内容", found.getContent());
    }
}
