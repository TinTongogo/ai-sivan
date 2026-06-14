package com.icusu.sivan.domain.memory;

import com.icusu.sivan.domain.memory.PatternFeatureVector;
import com.icusu.sivan.domain.memory.TaskFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstinctPatternTest {

    @Test
    void shouldCreateNewVersion() {
        var features = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        var vector = PatternFeatureVector.fromTaskFeatures(features);

        var original = new InstinctPattern();
        original.setPatternId(java.util.UUID.randomUUID());
        original.setFeatureVector(vector);
        original.setExecutionMode("SQUAD");
        original.setVersion(1);

        var next = original.newVersion();

        assertNull(next.getPatternId()); // 新模板尚未持久化
        assertEquals(original.getFeatureVector(), next.getFeatureVector());
        assertEquals(original.getExecutionMode(), next.getExecutionMode());
        assertEquals(original.getPatternId(), next.getSourcePatternId());
        assertEquals(2, next.getVersion());
        assertTrue(next.getActive());
    }

    @Test
    void shouldRecordHit() {
        var pattern = new InstinctPattern();
        pattern.recordHit();
        assertEquals(1, pattern.getHitCount());
        assertEquals(1, pattern.getTotalCount());
        assertNotNull(pattern.getLastMatchAt());
    }

    @Test
    void shouldRecordOutcome() {
        var pattern = new InstinctPattern();
        pattern.recordOutcome(true);
        assertEquals(1, pattern.getSuccessCount());
        assertEquals(1, pattern.getTotalCount());
    }

    @Test
    void shouldRecordOutcomeFailure() {
        var pattern = new InstinctPattern();
        pattern.recordOutcome(false);
        assertNull(pattern.getSuccessCount());
        assertEquals(1, pattern.getTotalCount());
    }
}
