package com.icusu.sivan.web;

import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.infra.orchestration.entity.SquadEntity;
import com.icusu.sivan.infra.orchestration.repository.SquadExecutionJpaRepository;
import com.icusu.sivan.infra.orchestration.repository.SquadJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

class SquadExecutionIT extends AbstractWebIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private SquadJpaRepository squadRepo;
    @Autowired
    private SquadExecutionJpaRepository executionRepo;

    @MockitoBean
    private Model model;

    @BeforeEach
    void cleanUp() {
        executionRepo.deleteAll();
        squadRepo.deleteAll();
    }

    @Test
    void shouldCreateExecutionOnPost() {
        SquadEntity squad = squadRepo.save(SquadEntity.builder().accountId(TEST_ACCOUNT_ID).name("Test Squad").mode("SEQUENTIAL").topologyJson("[{\"phase\":1,\"agents\":[\"agentA\",\"agentB\"]}]").build());

        webTestClient.post().uri("/api/squads/{squadId}/execute", squad.getSquadId()).bodyValue(Map.of("taskDescription", "测试任务")).exchange().expectStatus().isAccepted().expectBody().jsonPath("$.code").isEqualTo(202).jsonPath("$.data.executionId").isNotEmpty().jsonPath("$.data.squadId").isEqualTo(squad.getSquadId().toString()).jsonPath("$.data.taskDescription").isEqualTo("测试任务");

        webTestClient.get().uri("/api/squads/{squadId}/executions", squad.getSquadId()).exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(200).jsonPath("$.data.total").isEqualTo(1).jsonPath("$.data.items[0].taskDescription").isEqualTo("测试任务");
    }
}
