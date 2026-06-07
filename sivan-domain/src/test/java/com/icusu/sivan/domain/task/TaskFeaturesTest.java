package com.icusu.sivan.domain.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskFeaturesTest {

    @Test
    void shouldConstructWithAllDimensions() {
        var features = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        assertEquals(TaskFeatures.Complexity.LEVEL_3, features.complexity());
        assertEquals(TaskFeatures.Dependency.SEQUENTIAL, features.dependency());
        assertEquals(TaskFeatures.InputStructure.FREE_TEXT, features.inputStructure());
        assertEquals(TaskFeatures.Domain.WRITING, features.domain());
        assertEquals(TaskFeatures.OutputType.LONG_TEXT, features.outputType());
    }

    @Test
    void complexityShouldHaveFiveLevels() {
        assertEquals(5, TaskFeatures.Complexity.values().length);
    }

    @Test
    void dependencyShouldHaveFourTypes() {
        assertEquals(4, TaskFeatures.Dependency.values().length);
    }

    @Test
    void inputStructureShouldHaveFiveTypes() {
        assertEquals(5, TaskFeatures.InputStructure.values().length);
    }

    @Test
    void domainShouldHaveSixTypes() {
        assertEquals(6, TaskFeatures.Domain.values().length);
    }

    @Test
    void outputTypeShouldHaveSixTypes() {
        assertEquals(6, TaskFeatures.OutputType.values().length);
    }

    @Test
    void shouldBeEqualWithSameValues() {
        var a = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.GENERAL,
                TaskFeatures.OutputType.SHORT_TEXT
        );
        var b = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.GENERAL,
                TaskFeatures.OutputType.SHORT_TEXT
        );
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void shouldNotBeEqualWithDifferentValues() {
        var a = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.GENERAL,
                TaskFeatures.OutputType.SHORT_TEXT
        );
        var b = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.CODE,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.CODE
        );
        assertNotEquals(a, b);
    }
}
