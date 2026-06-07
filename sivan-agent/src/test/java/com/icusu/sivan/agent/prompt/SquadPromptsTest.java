package com.icusu.sivan.agent.prompt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SquadPromptsTest {

    @Test
    void topologyGenerateSystemStatic_hasSivanPersona() {
        Prompt p = SquadPrompts.TOPOLOGY_GENERATE_SYSTEM_STATIC;
        assertTrue(p.content().contains("灵枢"));
        assertEquals(Prompt.OutputFormat.JSON_ARRAY, p.outputFormat());
        assertEquals(Prompt.CacheStrategy.STATIC, p.cacheStrategy());
    }

    @Test
    void topologyGenerateSystemStatic_coversAllModes() {
        Prompt p = SquadPrompts.TOPOLOGY_GENERATE_SYSTEM_STATIC;
        assertTrue(p.content().contains("SEQUENTIAL"));
        assertTrue(p.content().contains("PARALLEL"));
        assertTrue(p.content().contains("CONDITIONAL"));
        assertTrue(p.content().contains("HIERARCHICAL"));
        assertTrue(p.content().contains("CONSENSUS"));
    }

    @Test
    void topologyGenerateUser_containsTask() {
        Prompt p = SquadPrompts.topologyGenerateUser("任务描述");
        assertTrue(p.content().contains("任务描述"));
        assertEquals(Prompt.CacheStrategy.DYNAMIC, p.cacheStrategy());
    }

    @Test
    void topologyGenerateUser_withAgents_includesAgentContext() {
        Prompt p = SquadPrompts.topologyGenerateUser("写一首诗", "- 诗人: 擅长创作");
        assertTrue(p.content().contains("写一首诗"));
        assertTrue(p.content().contains("- 诗人"));
        assertEquals(Prompt.OutputFormat.JSON_ARRAY, p.outputFormat());
    }

    @Test
    void deprecatedAliases_pointToNewConstants() {
        assertSame(SquadPrompts.TOPOLOGY_GENERATE_SYSTEM_STATIC, SquadPrompts.TOPOLOGY_GENERATE_SYSTEM);
    }

    @Test
    void squadNaming_hasSivanPersona() {
        Prompt p = SquadPrompts.squadNamingUser("write a novel");
        assertTrue(p.content().contains("灵枢"));
    }

    @Test
    void squadNamingSystem_hasSivanPersona() {
        Prompt p = SquadPrompts.SQUAD_NAMING_SYSTEM;
        assertTrue(p.content().contains("灵枢"));
    }
}
