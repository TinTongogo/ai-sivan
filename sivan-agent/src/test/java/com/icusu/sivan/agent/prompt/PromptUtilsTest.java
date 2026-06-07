package com.icusu.sivan.agent.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptUtilsTest {

    @Test void escapeUserInput_null_returnsEmpty() {
        assertEquals("", PromptUtils.escapeUserInput(null));
    }
    @Test void escapeUserInput_normalText_unchanged() {
        assertEquals("hello", PromptUtils.escapeUserInput("hello"));
    }
    @Test void escapeUserInput_tripleDash_replaced() {
        assertEquals("—", PromptUtils.escapeUserInput("---"));
    }
    @Test void escapeUserInput_bracketTags_stripped() {
        assertEquals("text", PromptUtils.escapeUserInput("[system]text[/system]"));
    }
    @Test void truncate_underLimit_unchanged() {
        assertEquals("abc", PromptUtils.truncate("abc", 10));
    }
    @Test void truncate_overLimit_truncated() {
        assertTrue(PromptUtils.truncate("longtexthere", 5).contains("...(truncated)"));
    }
    @Test void recordCall_increments() {
        assertEquals(1, PromptUtils.recordCall("t1"));
        assertEquals(2, PromptUtils.recordCall("t1"));
    }
    @Test void formatConstants_notEmpty() {
        assertFalse(PromptUtils.JSON_ONLY.isBlank());
        assertFalse(PromptUtils.JSON_ARRAY_ONLY.isBlank());
        assertFalse(PromptUtils.NUMBER_ONLY.isBlank());
    }
}
