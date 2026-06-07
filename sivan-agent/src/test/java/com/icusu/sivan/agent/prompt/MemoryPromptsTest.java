package com.icusu.sivan.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPromptsTest {

    @Test
    void coldCompressionUser_includesMaxTokens() {
        Prompt p = MemoryPrompts.coldCompressionUser("条目1\n条目2", 500);
        assertTrue(p.content().contains("500"));
        assertTrue(p.content().contains("条目1"));
        assertEquals(Prompt.OutputFormat.JSON_OBJECT, p.outputFormat());
    }

    @Test
    void coldCompressionSystem_hasSivanPersona() {
        assertTrue(MemoryPrompts.COLD_COMPRESSION_SYSTEM.content().contains("灵枢"));
    }

    @Test
    void exchangeSummary_includesUserAndAssistant() {
        Prompt p = MemoryPrompts.exchangeSummary("用户问题", "助手回答");
        assertTrue(p.content().contains("用户问题"));
        assertTrue(p.content().contains("助手回答"));
        assertTrue(p.content().contains("不超过80字"));
    }

    @Test
    void exchangeSummary_noAssistant() {
        Prompt p = MemoryPrompts.exchangeSummary("用户问题", null);
        assertTrue(p.content().contains("用户问题"));
        assertFalse(p.content().contains("助手:"));
    }
}
