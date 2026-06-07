package com.icusu.sivan.web.conversation.service.tree;

import com.icusu.sivan.common.enums.ExecutionStatus;
import com.icusu.sivan.domain.orchestration.Contract;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import com.icusu.sivan.domain.orchestration.Squad;
import com.icusu.sivan.domain.orchestration.SquadExecution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SquadTreeTest {

    @Test
    void empty_returnsEmpty() {
        SquadTree tree = new SquadTree();
        String result = tree.buildContext("CHAT", 1000);
        assertTrue(result.isEmpty());
    }

    @Test
    void squadWithoutPhases_returnsBasicInfo() {
        Squad squad = Squad.builder().name("测试Squad").build();
        SquadExecution exec = SquadExecution.builder()
                .status(ExecutionStatus.RUNNING)
                .taskDescription("分析数据")
                .currentPhase(0)
                .build();

        String result = new SquadTree()
                .withSquad(squad).withExecution(exec)
                .buildContext("SQUAD_ACTIVE", 1000);

        assertNotNull(result);
        assertTrue(result.contains("测试Squad"));
        assertTrue(result.contains("RUNNING"));
        assertTrue(result.contains("分析数据"));
    }

    @Test
    void completedPhases_areFolded() {
        PhaseNode phase0 = PhaseNode.builder().phase(0).name("分析").description("分析需求").build();
        PhaseNode phase1 = PhaseNode.builder().phase(1).name("执行").description("执行计划").build();
        PhaseNode phase2 = PhaseNode.builder().phase(2).name("总结").description("输出报告").build();

        Squad squad = Squad.builder().name("数据分析Squad").phases(List.of(phase0, phase1, phase2)).build();
        SquadExecution exec = SquadExecution.builder()
                .status(ExecutionStatus.RUNNING)
                .taskDescription("数据分析")
                .currentPhase(1) // phase 0 已完成, phase 1 运行中
                .build();

        Contract phase0contract = Contract.builder()
                .phase(0).sourceAgent("分析Agent").targetAgent("执行Agent")
                .content("需求已分析完成，需要处理销售数据").build();

        String result = new SquadTree()
                .withSquad(squad).withExecution(exec)
                .withContracts(List.of(phase0contract))
                .buildContext("SQUAD_ACTIVE", 1000);

        assertNotNull(result);
        assertTrue(result.contains("数据分析Squad"));
        // 已完成阶段：折叠（不包含展开内容标记 "→" 的特定格式）
        assertTrue(result.contains("分析"));
        // 运行中阶段：展开标记
        assertTrue(result.contains("执行"));
        assertTrue(result.contains("执行中"));
        // 未执行阶段
        assertTrue(result.contains("总结"));
    }

    @Test
    void tightBudget_triggersCompactMode() {
        PhaseNode phase0 = PhaseNode.builder().phase(0).name("分析阶段").build();
        PhaseNode phase1 = PhaseNode.builder().phase(1).name("执行阶段").build();
        PhaseNode phase2 = PhaseNode.builder().phase(2).name("总结阶段").build();

        Squad squad = Squad.builder().name("LongSquadName").phases(List.of(phase0, phase1, phase2)).build();
        SquadExecution exec = SquadExecution.builder()
                .status(ExecutionStatus.RUNNING)
                .taskDescription("long task")
                .currentPhase(1)
                .build();

        // 非常小的预算触发 compact 模式
        String result = new SquadTree()
                .withSquad(squad).withExecution(exec)
                .buildContext("SQUAD_ACTIVE", 10);

        assertNotNull(result);
        assertTrue(result.contains("▶")); // compact 模式有 ▶ 标记
    }

    @Test
    void estimateTokens_returnsCachedValue() {
        SquadTree tree = new SquadTree()
                .withSquad(Squad.builder().name("Test").phases(List.of(
                        PhaseNode.builder().phase(0).name("阶段0").build()
                )).build())
                .withExecution(SquadExecution.builder().status(ExecutionStatus.RUNNING).currentPhase(0).build());

        tree.buildContext("SQUAD_ACTIVE", 1000);
        assertTrue(tree.estimateTokens() > 0);
    }

    @Test
    void treeType_isSquad() {
        assertEquals("squad", new SquadTree().treeType());
    }
}
