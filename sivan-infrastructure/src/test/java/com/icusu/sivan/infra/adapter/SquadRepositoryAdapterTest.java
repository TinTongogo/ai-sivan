package com.icusu.sivan.infra.orchestration.adapter;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.enums.SquadSource;
import com.icusu.sivan.domain.orchestration.ISquadRepository;
import com.icusu.sivan.domain.orchestration.Squad;
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
class SquadRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private ISquadRepository repository;

    @Test
    void shouldSaveAndFindById() {
        Squad squad = Squad.builder()
                .accountId(UUID.randomUUID())
                .name("测试Squad")
                .description("测试描述")
                .mode(SquadMode.SEQUENTIAL)
                .source(SquadSource.USER)
                .active(true)
                .build();
        repository.save(squad);

        assertNotNull(squad.getSquadId());

        Squad found = repository.findById(squad.getSquadId()).orElse(null);
        assertNotNull(found);
        assertEquals("测试Squad", found.getName());
        assertEquals(SquadMode.SEQUENTIAL, found.getMode());
        assertTrue(found.getActive());
    }

    @Test
    void shouldFindAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository.save(Squad.builder()
                    .accountId(accountId).name("Squad" + i)
                    .mode(SquadMode.PARALLEL).source(SquadSource.USER).active(true)
                    .build());
        }

        List<Squad> squads = repository.findAllByAccount(accountId);
        assertEquals(3, squads.size());
    }

    @Test
    void shouldFindAllByAccountAndProject() {
        UUID accountId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        repository.save(Squad.builder()
                .accountId(accountId).projectId(projectId).name("项目Squad")
                .mode(SquadMode.SEQUENTIAL).source(SquadSource.USER).active(true)
                .build());

        List<Squad> squads = repository.findAllByAccountAndProject(accountId, projectId);
        assertEquals(1, squads.size());
    }

    @Test
    void shouldFindAllByAccountAndActiveTrue() {
        UUID accountId = UUID.randomUUID();
        repository.save(Squad.builder()
                .accountId(accountId).name("活跃").mode(SquadMode.SEQUENTIAL)
                .source(SquadSource.USER).active(true).build());
        repository.save(Squad.builder()
                .accountId(accountId).name("停用").mode(SquadMode.SEQUENTIAL)
                .source(SquadSource.USER).active(false).build());

        List<Squad> active = repository.findAllByAccountAndActiveTrue(accountId);
        assertEquals(1, active.size());
        assertTrue(active.get(0).getActive());
    }

    @Test
    void shouldUpdateSquad() {
        Squad squad = Squad.builder()
                .accountId(UUID.randomUUID()).name("旧名")
                .mode(SquadMode.SEQUENTIAL).source(SquadSource.USER).active(true)
                .build();
        repository.save(squad);

        squad.setName("新名");
        squad.setActive(false);
        repository.update(squad);

        Squad found = repository.findById(squad.getSquadId()).orElse(null);
        assertNotNull(found);
        assertEquals("新名", found.getName());
        assertFalse(found.getActive());
    }

    @Test
    void shouldDelete() {
        Squad squad = Squad.builder()
                .accountId(UUID.randomUUID()).name("待删除")
                .mode(SquadMode.SEQUENTIAL).source(SquadSource.USER).active(true)
                .build();
        repository.save(squad);
        UUID id = squad.getSquadId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }
}
