package com.icusu.sivan.agent.prompt;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrchestrationPromptsTest {

    // ============================================================
    // 共享常量
    // ============================================================

    @Test
    void modeOverview_coversAllFiveModes() {
        assertTrue(OrchestrationPrompts.MODE_OVERVIEW.contains("SEQUENTIAL"));
        assertTrue(OrchestrationPrompts.MODE_OVERVIEW.contains("PARALLEL"));
        assertTrue(OrchestrationPrompts.MODE_OVERVIEW.contains("CONDITIONAL"));
        assertTrue(OrchestrationPrompts.MODE_OVERVIEW.contains("HIERARCHICAL"));
        assertTrue(OrchestrationPrompts.MODE_OVERVIEW.contains("CONSENSUS"));
    }

    // ============================================================
    // O3 合并 ORCHESTRATION_SYSTEM
    // ============================================================

    @Test
    void orchestrationSystem_hasPersonaAndAllModes() {
        Prompt p = OrchestrationPrompts.ORCHESTRATION_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
        assertTrue(p.content().contains("SEQUENTIAL"));
        assertTrue(p.content().contains("PARALLEL"));
        assertTrue(p.content().contains("CONDITIONAL"));
        assertTrue(p.content().contains("HIERARCHICAL"));
        assertTrue(p.content().contains("CONSENSUS"));
        assertEquals(Prompt.CacheStrategy.STATIC, p.cacheStrategy());
    }

    // ============================================================
    // CONDITIONAL User prompt
    // ============================================================

    @Test
    void conditionalRouteUser_includesPhasesAndOutput() {
        List<OrchestrationPrompts.PhaseInfo> phases = List.of(
                new OrchestrationPrompts.PhaseInfo("p1", "d1"),
                new OrchestrationPrompts.PhaseInfo("p2", "d2"),
                new OrchestrationPrompts.PhaseInfo("p3", "d3")
        );
        Prompt p = OrchestrationPrompts.conditionalRouteUser(phases, 0, "output");
        assertTrue(p.content().contains("p2"));
        assertTrue(p.content().contains("p3"));
        assertTrue(p.content().contains("output"));
        assertEquals(Prompt.OutputFormat.SINGLE_NUMBER, p.outputFormat());
    }

    // ============================================================
    // HIERARCHICAL User prompt
    // ============================================================

    @Test
    void hierarchicalDecomposeUser_includesTask() {
        Prompt p = OrchestrationPrompts.hierarchicalDecomposeUser("task description");
        assertTrue(p.content().contains("task description"));
        assertTrue(p.content().contains("subtasks"));
        assertEquals(Prompt.OutputFormat.JSON_OBJECT, p.outputFormat());
    }

    @Test
    void subTaskUser_includesGoalAndInput() {
        Prompt p = OrchestrationPrompts.subTaskUser("目标", "输入", "期望", "依赖摘要");
        assertTrue(p.content().contains("目标"));
        assertTrue(p.content().contains("输入"));
        assertTrue(p.content().contains("期望"));
        assertTrue(p.content().contains("依赖摘要"));
    }

    @Test
    void subTaskUser_nullFieldsStillProducesPrompt() {
        Prompt p = OrchestrationPrompts.subTaskUser("目标", null, null, null);
        assertTrue(p.content().contains("目标"));
        assertEquals(Prompt.CacheStrategy.DYNAMIC, p.cacheStrategy());
    }

    // ============================================================
    // CONSENSUS User prompt
    // ============================================================

    @Test
    void consensusSynthesisUser_includesAgentResults() {
        Map<String, String> results = new java.util.LinkedHashMap<>();
        results.put("agent1", "result1");
        results.put("agent2", "result2");
        Prompt p = OrchestrationPrompts.consensusSynthesisUser(results);
        assertTrue(p.content().contains("agent1"));
        assertTrue(p.content().contains("result1"));
        assertTrue(p.content().contains("agent2"));
        assertEquals(Prompt.OutputFormat.JSON_OBJECT, p.outputFormat());
    }

    @Test
    void consensusSecondRoundUser_hasDissentInfo() {
        Prompt p = OrchestrationPrompts.consensusSecondRoundUser(
                "task", "majority", "dissent points", "prev answer");
        assertTrue(p.content().contains("task"));
        assertTrue(p.content().contains("majority"));
        assertTrue(p.content().contains("dissent points"));
        assertTrue(p.content().contains("prev answer"));
    }

    @Test
    void consensusInterPhaseUser_hasPhaseResults() {
        List<Map.Entry<String, String>> results = List.of(
                Map.entry("phaseA", "resultA"),
                Map.entry("phaseB", "resultB"));
        Prompt p = OrchestrationPrompts.consensusInterPhaseUser(results);
        assertTrue(p.content().contains("phaseA"));
        assertTrue(p.content().contains("resultB"));
    }

    // ============================================================
    // 通用
    // ============================================================

    @Test
    void phaseTaskUser_dynamic() {
        Prompt p = OrchestrationPrompts.phaseTaskUser("input", "description");
        assertTrue(p.content().contains("input"));
        assertTrue(p.content().contains("description"));
    }

    @Test
    void phaseTaskUser_defaultDescription() {
        Prompt p = OrchestrationPrompts.phaseTaskUser("input", null);
        assertTrue(p.content().contains("input"));
        assertTrue(p.content().contains("请根据上述任务继续处理"));
    }
}
