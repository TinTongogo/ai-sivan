package com.icusu.sivan.orch.executor;

import com.icusu.sivan.agent.mcp.McpConnectionManager;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.strategy.ReActExecutionStrategy;
import com.icusu.sivan.agent.tool.ToolEnricher;
import com.icusu.sivan.agent.tool.ToolResolver;
import com.icusu.sivan.core.tool.ToolProvider;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.agent.ISkillRepository;
import com.icusu.sivan.domain.orchestration.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SquadExecutionEngineTest {

    @Mock private PhaseScheduler phaseScheduler;
    @Mock private ModelRouter modelRouter;
    @Mock private ToolProvider toolProvider;
    @Mock private ReActExecutionStrategy executionStrategy;
    @Mock private ToolResolver toolResolver;
    @Mock private ISquadExecutionRepository squadExecutionRepository;
    @Mock private ISquadRepository squadRepository;
    @Mock private IContractRepository contractRepository;
    @Mock private IHitlReviewRepository hitlReviewRepository;
    @Mock private IAgentRepository agentRepository;
    @Mock private ISkillRepository skillRepository;
    @Mock private McpConnectionManager mcpConnectionManager;
    @Mock private ToolEnricher toolEnricher;
    @Mock private IExecutionArtifactRepository executionArtifactRepository;

    private SquadExecutionEngine engine;
    private final UUID executionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID squadId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        engine = new SquadExecutionEngine(phaseScheduler, modelRouter, toolProvider,
                executionStrategy, toolResolver, squadExecutionRepository,
                squadRepository, contractRepository, hitlReviewRepository,
                agentRepository, skillRepository, mcpConnectionManager,
                toolEnricher, executionArtifactRepository);
        // 自注入代理，供 @Async 内部调用使用
        var field = SquadExecutionEngine.class.getDeclaredField("self");
        field.setAccessible(true);
        field.set(engine, engine);
    }

    @Test
    void engineType_shouldReturnAsync() {
        assertEquals("ASYNC", engine.engineType());
    }

    @Test
    void events_shouldFilterByExecutionId() {
        var flux = engine.events(executionId);
        assertNotNull(flux);
    }

    @Test
    void allExecutionEvents_shouldReturnAllEvents() {
        var flux = engine.allExecutionEvents();
        assertNotNull(flux);
    }

    @Test
    void cancel_shouldUpdateStatusAndSetFlag() {
        engine.cancel(executionId);
        verify(squadExecutionRepository).updateStatus(executionId, "CANCELLING", null);
    }

    @Test
    void execute_shouldUpdateStatusToRunning() {
        Squad squad = Squad.builder().squadId(squadId).phases(List.of()).build();
        SquadExecution execution = SquadExecution.builder()
                .executionId(executionId)
                .squadId(squadId)
                .accountId(accountId)
                .taskDescription("test")
                .status(null)
                .build();

        engine.execute(squad, execution, accountId);

        verify(squadExecutionRepository).updateStatus(eq(executionId), eq("RUNNING"), any());
    }

    @Test
    void retryPhase_shouldDoNothing_whenExecutionNotFound() {
        when(squadExecutionRepository.findById(executionId))
                .thenReturn(Optional.empty());

        SquadExecution execution = SquadExecution.builder()
                .executionId(executionId).build();

        engine.retryPhase(execution, accountId);

        verify(squadExecutionRepository, never()).updateStatus(any(), anyString(), any());
    }

    @Test
    void resume_shouldDoNothing_whenExecutionNotFound() {
        when(squadExecutionRepository.findById(executionId))
                .thenReturn(Optional.empty());

        SquadExecution execution = SquadExecution.builder()
                .executionId(executionId).build();

        engine.resume(execution, accountId);

        verify(squadExecutionRepository, never()).updateStatus(any(), anyString(), any());
    }
}
