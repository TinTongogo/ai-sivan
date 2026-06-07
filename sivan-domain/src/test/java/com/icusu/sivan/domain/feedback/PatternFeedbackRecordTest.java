package com.icusu.sivan.domain.feedback;

import com.icusu.sivan.domain.task.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternFeedbackRecordTest {

    @Test
    void shouldCreateSuccessRecord() {
        var features = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        var record = PatternFeedbackRecord.builder()
                .feedbackId(java.util.UUID.randomUUID())
                .patternId(java.util.UUID.randomUUID())
                .accountId(java.util.UUID.randomUUID())
                .executionId(java.util.UUID.randomUUID())
                .actualFeatures(features)
                .taskDescription("写一篇文章")
                .outcome(PatternFeedbackRecord.FeedbackOutcome.SUCCESS)
                .tokenCost(1500)
                .source("TRIGGER_PATTERN")
                .build();

        assertNotNull(record.getFeedbackId());
        assertEquals(PatternFeedbackRecord.FeedbackOutcome.SUCCESS, record.getOutcome());
        assertEquals(1500, record.getTokenCost());
    }

    @Test
    void shouldCreateFailureRecordWithDeviation() {
        var deviation = new FeatureDeviation(
                true, 0.65, 0.30,
                List.of("dependency"),
                "dependency → PARALLEL"
        );
        var record = PatternFeedbackRecord.builder()
                .accountId(java.util.UUID.randomUUID())
                .outcome(PatternFeedbackRecord.FeedbackOutcome.FAILURE)
                .outcomeReason("依赖类型错误")
                .deviation(deviation)
                .source("TRIGGER_LLM")
                .build();

        assertEquals(PatternFeedbackRecord.FeedbackOutcome.FAILURE, record.getOutcome());
        assertNotNull(record.getDeviation());
        assertTrue(record.getDeviation().featureMismatch());
        assertEquals(1, record.getDeviation().mismatchDimensions().size());
    }

    @Test
    void shouldAllowNullPatternId() {
        var record = PatternFeedbackRecord.builder()
                .accountId(java.util.UUID.randomUUID())
                .outcome(PatternFeedbackRecord.FeedbackOutcome.SUCCESS)
                .source("TRIGGER_LLM")
                .build();
        assertNull(record.getPatternId());
    }

    @Test
    void outcomeEnumShouldHaveThreeValues() {
        assertEquals(3, PatternFeedbackRecord.FeedbackOutcome.values().length);
    }
}
