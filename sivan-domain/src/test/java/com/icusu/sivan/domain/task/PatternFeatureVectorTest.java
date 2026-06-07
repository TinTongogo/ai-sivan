package com.icusu.sivan.domain.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatternFeatureVectorTest {

    @Test
    void shouldReturnMaxScoreForExactMatch() {
        var features = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        var vector = PatternFeatureVector.fromTaskFeatures(features);
        double score = vector.matchScore(features);
        assertTrue(score >= 0.9, "精确匹配得分应 ≥ 0.9，实际: " + score);
    }

    @Test
    void shouldReturnLowScoreForNoMatch() {
        var query = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.GENERAL,
                TaskFeatures.OutputType.SHORT_TEXT
        );
        var vector = PatternFeatureVector.fromTaskFeatures(new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_5,
                TaskFeatures.Dependency.CONDITIONAL,
                TaskFeatures.InputStructure.STRUCTURED_DATA,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.CODE
        ));
        double score = vector.matchScore(query);
        assertTrue(score <= 0.4, "完全不匹配得分应 ≤ 0.4，实际: " + score);
    }

    @Test
    void shouldReturnPartialScoreForPartialMatch() {
        var query = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        var vector = PatternFeatureVector.fromTaskFeatures(new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        ));
        // complexity + inputStructure + domain + outputType match (0.30+0.15+0.20+0.10 = 0.75)
        // dependency mismatch
        double score = vector.matchScore(query);
        assertTrue(score > 0.4 && score < 0.9, "部分匹配得分应在 0.4~0.9 之间，实际: " + score);
    }

    @Test
    void weightsShouldSumToOne() {
        double sum = PatternFeatureVector.WEIGHT_COMPLEXITY
                + PatternFeatureVector.WEIGHT_DEPENDENCY
                + PatternFeatureVector.WEIGHT_DOMAIN
                + PatternFeatureVector.WEIGHT_INPUT_STRUCTURE
                + PatternFeatureVector.WEIGHT_OUTPUT_TYPE;
        assertEquals(1.0, sum, 0.001);
    }

    @Test
    void mergeShouldUpdateDistribution() {
        var initial = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        var vector = PatternFeatureVector.fromTaskFeatures(initial);

        var sample = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_4,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        var merged = vector.merge(sample, 0.3);

        // After merge, LEVEL_3 should be 0.7, LEVEL_4 should be 0.3
        assertEquals(0.7, merged.getComplexity().get(TaskFeatures.Complexity.LEVEL_3), 0.01);
        assertEquals(0.3, merged.getComplexity().get(TaskFeatures.Complexity.LEVEL_4), 0.01);

        // SEQUENTIAL was 1.0, now should be 1.0 (same in sample)
        assertEquals(1.0, merged.getDependency().get(TaskFeatures.Dependency.SEQUENTIAL), 0.01);
    }

    @Test
    void shouldBuildWithBuilder() {
        var vector = PatternFeatureVector.builder()
                .complexity(TaskFeatures.Complexity.LEVEL_2, 0.8)
                .complexity(TaskFeatures.Complexity.LEVEL_3, 0.2)
                .dependency(TaskFeatures.Dependency.INDEPENDENT, 1.0)
                .inputStructure(TaskFeatures.InputStructure.Q_A, 1.0)
                .domain(TaskFeatures.Domain.ANALYSIS, 0.9)
                .domain(TaskFeatures.Domain.GENERAL, 0.1)
                .outputType(TaskFeatures.OutputType.SHORT_TEXT, 1.0)
                .build();

        assertEquals(0.8, vector.getComplexity().get(TaskFeatures.Complexity.LEVEL_2), 0.01);
        assertEquals(0.2, vector.getComplexity().get(TaskFeatures.Complexity.LEVEL_3), 0.01);
        assertEquals(1.0, vector.getDependency().get(TaskFeatures.Dependency.INDEPENDENT), 0.01);
    }
}
