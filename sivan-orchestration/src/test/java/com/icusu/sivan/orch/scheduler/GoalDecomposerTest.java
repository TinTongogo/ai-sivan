package com.icusu.sivan.orch.scheduler;

import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.orch.topology.TopologyGenerator;
import com.icusu.sivan.orch.topology.TopologyResult;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.core.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalDecomposerTest {

    @Mock private ModelRouter modelRouter;
    @Mock private TopologyGenerator topologyGenerator;
    @Mock private Model model;

    private GoalDecomposer decomposer;

    @BeforeEach
    void setUp() {
        // TopologyGenerator 返回空拓扑，让 decompose 测试走 LLM 回退路径
        when(topologyGenerator.generateForNewSquad(any(UUID.class), anyString()))
                .thenReturn(Mono.just(TopologyResult.builder().phases(List.of()).build()));
        decomposer = new GoalDecomposer(modelRouter, topologyGenerator);
    }

    @Test
    void decompose_LLM返回有效JSON() {
        String llmJson = "[\n" +
                "  {\"name\": \"准备阶段\", \"description\": \"准备工作\", \"tasks\": [\n" +
                "    {\"description\": \"创建项目结构\"},\n" +
                "    {\"description\": \"配置依赖\"}\n" +
                "  ]},\n" +
                "  {\"name\": \"开发阶段\", \"description\": \"核心开发\", \"tasks\": [\n" +
                "    {\"description\": \"实现API\"}\n" +
                "  ]}\n" +
                "]";

        when(modelRouter.getDefaultModel(any(UUID.class))).thenReturn(model);
        when(model.chat(anyList(), any(Model.ModelParams.class)))
                .thenReturn(Mono.just(new Model.ModelResponse(Msg.of(Role.ASSISTANT, llmJson), TokenUsage.EMPTY)));

        var goal = decomposer.decompose("测试目标", "测试描述", UUID.randomUUID(), null).block();
        assertNotNull(goal);
        assertEquals("测试目标", goal.getTitle());
        assertEquals(2, goal.getMilestones().size());
        assertEquals("准备阶段", goal.getMilestones().get(0).getName());
        assertEquals(2, goal.getMilestones().get(0).getTasks().size());
        assertEquals(1, goal.getMilestones().get(1).getTasks().size());
        assertEquals(3, goal.getTotalTasks());
    }

    @Test
    void decompose_LLM返回空JSON_创建默认里程碑() {
        when(modelRouter.getDefaultModel(any(UUID.class))).thenReturn(model);
        when(model.chat(anyList(), any(Model.ModelParams.class)))
                .thenReturn(Mono.just(new Model.ModelResponse(Msg.of(Role.ASSISTANT, "普通文本回复无JSON"), TokenUsage.EMPTY)));

        var goal = decomposer.decompose("测试", "描述", UUID.randomUUID(), null).block();
        assertNotNull(goal);
        assertEquals(1, goal.getMilestones().size());
        assertEquals("执行", goal.getMilestones().get(0).getName());
        assertEquals(1, goal.getTotalTasks());
    }

    @Test
    void decompose_LLM异常_创建默认里程碑() {
        when(modelRouter.getDefaultModel(any(UUID.class))).thenReturn(model);
        when(model.chat(anyList(), any(Model.ModelParams.class)))
                .thenReturn(Mono.error(new RuntimeException("API 超时")));

        var goal = decomposer.decompose("测试", "描述", UUID.randomUUID(), null).block();
        assertNotNull(goal);
        assertEquals(1, goal.getMilestones().size());
        assertEquals("执行", goal.getMilestones().get(0).getName());
    }

    @Test
    void decompose_LLM返回空文本() {
        when(modelRouter.getDefaultModel(any(UUID.class))).thenReturn(model);
        when(model.chat(anyList(), any(Model.ModelParams.class)))
                .thenReturn(Mono.just(new Model.ModelResponse(Msg.of(Role.ASSISTANT, ""), TokenUsage.EMPTY)));

        var goal = decomposer.decompose("测试", "描述", UUID.randomUUID(), null).block();
        assertNotNull(goal);
        assertEquals(1, goal.getMilestones().size());
    }
}
