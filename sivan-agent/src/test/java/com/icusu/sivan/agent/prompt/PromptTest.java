package com.icusu.sivan.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTest {

    @Test
    void constructorAndAccessors() {
        Prompt p = new Prompt("test", Prompt.CacheStrategy.STATIC, 100, Prompt.OutputFormat.JSON_OBJECT);
        assertEquals("test", p.content());
        assertEquals(Prompt.CacheStrategy.STATIC, p.cacheStrategy());
        assertEquals(100, p.estimatedTokens());
        assertEquals(Prompt.OutputFormat.JSON_OBJECT, p.outputFormat());
    }

    @Test
    void empty() {
        assertTrue(Prompt.EMPTY.isEmpty());
        assertEquals("", Prompt.EMPTY.content());
    }

    @Test
    void isEmpty_nullContent() {
        Prompt p = new Prompt(null, Prompt.CacheStrategy.DYNAMIC, 0, Prompt.OutputFormat.FREE_TEXT);
        assertTrue(p.isEmpty());
    }

    @Test
    void isEmpty_blankContent() {
        Prompt p = new Prompt("  ", Prompt.CacheStrategy.DYNAMIC, 0, Prompt.OutputFormat.FREE_TEXT);
        assertTrue(p.isEmpty());
    }

    @Test
    void cacheStrategy_enum() {
        assertEquals(3, Prompt.CacheStrategy.values().length);
    }

    @Test
    void outputFormat_enum() {
        assertEquals(4, Prompt.OutputFormat.values().length);
    }
}
