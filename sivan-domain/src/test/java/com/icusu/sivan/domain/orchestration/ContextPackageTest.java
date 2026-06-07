package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextPackageTest {

    @Test
    void shouldCreateFromString() {
        var ctx = new ContextPackage("hello");
        assertEquals("hello", ctx.getInput());
        assertEquals("hello", ctx.getTaskDescription());
        assertTrue(ctx.getPhaseOutputs().isEmpty());
        assertNull(ctx.getHitlOverride());
    }

    @Test
    void shouldCreateFromPhaseResult() {
        var output = new PhaseOutput("phase output");
        var ctx = ContextPackage.fromPhaseResult("result", "task", Map.of(0, output));
        assertEquals("result", ctx.getInput());
        assertEquals("task", ctx.getTaskDescription());
        assertEquals(1, ctx.getPhaseOutputs().size());
        assertEquals("phase output", ctx.getPhaseOutputs().get(0).content());
    }

    @Test
    void shouldAppendPhaseOutput() {
        var ctx = new ContextPackage("initial");
        var output = new PhaseOutput("phase 1 result");
        var updated = ctx.withPhaseOutput(1, output);

        // original unchanged
        assertTrue(ctx.getPhaseOutputs().isEmpty());

        // new has the output
        assertEquals(1, updated.getPhaseOutputs().size());
        assertEquals("phase 1 result", updated.getPhaseOutputs().get(1).content());
        assertEquals("phase 1 result", updated.getInput());
    }
}
