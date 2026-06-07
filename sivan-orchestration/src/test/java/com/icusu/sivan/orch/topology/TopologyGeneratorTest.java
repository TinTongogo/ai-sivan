package com.icusu.sivan.orch.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.Model.ModelParams;
import com.icusu.sivan.core.model.Model.ModelResponse;
import com.icusu.sivan.domain.agent.AgentDefinition;
import com.icusu.sivan.domain.agent.IAgentRepository;
import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.task.TaskFeatures;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.memory.instinct.InstinctPatternService;
import com.icusu.sivan.memory.pattern.FeatureExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TopologyGeneratorTest {

    @Mock
    private InstinctPatternService instinctPatternService;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private Model model;
    @Mock
    private IAgentRepository agentRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private IPatternFeedbackRepository patternFeedbackRepository;
    @Mock
    private FeatureExtractor featureExtractor;

    private ObjectMapper objectMapper;
    private TopologyGenerator generator;
    private final UUID accountId = UUID.randomUUID();
    private final String taskDescription = "Java 后端 API 开发任务";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        generator = new TopologyGenerator(instinctPatternService, modelRouter, objectMapper,
                agentRepository, embeddingService, patternFeedbackRepository, featureExtractor);
        // 默认从 taskDescription 提取特征
        when(featureExtractor.extractHeuristic(taskDescription))
                .thenReturn(new TaskFeatures(TaskFeatures.Complexity.LEVEL_2, TaskFeatures.Dependency.INDEPENDENT,
                        TaskFeatures.InputStructure.FREE_TEXT, TaskFeatures.Domain.GENERAL, TaskFeatures.OutputType.SHORT_TEXT));
    }

    @Test
    void generate_shouldUseTemplate_whenMatched() {
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .topologyJson("[{\"phase\":0,\"name\":\"代码审查\",\"agents\":[\"reviewer\"],\"description\":\"审查代码\"}]")
                .build();
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.of(pattern));

        TopologyResult result = generator.generate(accountId, taskDescription).block();

        assertTrue(result.isFromPattern());
        assertEquals(SquadMode.SEQUENTIAL, result.getMode());
        assertEquals(1, result.getPhases().size());
        assertEquals("代码审查", result.getPhases().get(0).getName());
    }

    @Test
    void generate_shouldFallbackToLlm_whenNoTemplateMatch() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.empty());
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());
        String llmJson = "[{\"phase\":0,\"name\":\"需求分析\",\"agents\":[\"analyst\"],\"description\":\"分析需求\"}]";
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, List.of(new Content.Text(llmJson))), null)));

        TopologyResult result = generator.generate(accountId, taskDescription).block();

        assertFalse(result.isFromPattern());
        assertFalse(result.getPhases().isEmpty());
        assertEquals("需求分析", result.getPhases().get(0).getName());
    }

    @Test
    void generate_shouldUseDefault_whenLlmReturnsInvalidJson() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.empty());
        AgentDefinition agent = AgentDefinition.builder().agentName("default-agent").build();
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of(agent));
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, List.of(new Content.Text("invalid response"))), null)));

        TopologyResult result = generator.generate(accountId, taskDescription).block();

        assertFalse(result.isFromPattern());
        assertEquals(1, result.getPhases().size());
        assertEquals("任务执行", result.getPhases().get(0).getName());
        assertEquals(List.of("default-agent"), result.getPhases().get(0).getAgents());
    }

    @Test
    void generate_shouldUseDefault_whenLlmReturnsEmpty() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.empty());
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, List.of(new Content.Text(""))), null)));

        TopologyResult result = generator.generate(accountId, taskDescription).block();

        assertEquals(1, result.getPhases().size());
        assertEquals("任务执行", result.getPhases().get(0).getName());
    }

    @Test
    void generate_shouldUseTemplateOnlyIfHasValidPhases() {
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .topologyJson("{\"phases\":[]}")
                .build();
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.of(pattern));
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());
        String llmJson = "[{\"phase\":0,\"name\":\"开发\",\"agents\":[\"dev\"],\"description\":\"开发\"}]";
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, List.of(new Content.Text(llmJson))), null)));

        TopologyResult result = generator.generate(accountId, taskDescription).block();
        assertFalse(result.isFromPattern());
        assertEquals("开发", result.getPhases().get(0).getName());
    }

    @Test
    void parseLlmResponse_shouldHandleNull() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.empty());
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, List.of(new Content.Text(""))), null)));

        TopologyResult result = generator.generate(accountId, taskDescription).block();
        assertNotNull(result.getPhases());
        assertFalse(result.getPhases().isEmpty());
    }

    @Test
    void generate_shouldHandleMultiplePhases() {
        when(instinctPatternService.match(any(TaskFeatures.class), eq(accountId))).thenReturn(Optional.empty());
        when(agentRepository.findAllByAccount(accountId)).thenReturn(List.of());
        String llmJson = "[{\"phase\":0,\"name\":\"设计\",\"agents\":[\"architect\"],\"description\":\"设计\"}," +
                "{\"phase\":1,\"name\":\"开发\",\"agents\":[\"dev\"],\"description\":\"编码\"}," +
                "{\"phase\":2,\"name\":\"测试\",\"agents\":[\"tester\"],\"description\":\"测试\"}]";
        when(modelRouter.getDefaultModel(any())).thenReturn(model);
        when(model.chat(anyList(), any(ModelParams.class)))
                .thenReturn(Mono.just(new ModelResponse(
                        Msg.of(Role.ASSISTANT, List.of(new Content.Text(llmJson))), null)));

        TopologyResult result = generator.generate(accountId, taskDescription).block();
        assertEquals(3, result.getPhases().size());
        assertEquals("测试", result.getPhases().get(2).getName());
    }
}
