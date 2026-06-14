package com.icusu.sivan.infra.memory.instinct;

import com.icusu.sivan.domain.memory.FeatureDeviation;
import com.icusu.sivan.domain.memory.PatternFeedbackRecord;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.PatternFeatureVector;
import com.icusu.sivan.domain.memory.TaskFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstinctPatternServiceT1T2Test {

    @Mock
    private IInstinctPatternRepository patternRepository;
    @Mock
    private ISharedTemplateRepository sharedTemplateRepository;

    @Captor
    private ArgumentCaptor<InstinctPattern> patternCaptor;

    private InstinctPatternService service;
    private final UUID accountId = UUID.randomUUID();
    private final String topologyJson = "{\"phases\":[{\"name\":\"A\"}]}";

    private TaskFeatures features;
    private PatternFeedbackRecord.FeedbackOutcome success;
    private PatternFeedbackRecord.FeedbackOutcome failure;

    @BeforeEach
    void setUp() {
        service = new InstinctPatternService(patternRepository, sharedTemplateRepository);
        features = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.CODE);
        success = PatternFeedbackRecord.FeedbackOutcome.SUCCESS;
        failure = PatternFeedbackRecord.FeedbackOutcome.FAILURE;
    }

    @Test
    void t1_shouldCreateNewTemplate_whenPatternMissed() {
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(features)
                .taskDescription("编写一个 REST API")
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);

        verify(patternRepository).save(patternCaptor.capture());
        InstinctPattern saved = patternCaptor.getValue();
        assertNotNull(saved.getFeatureVector());
        assertEquals("SQUAD", saved.getExecutionMode());
        assertEquals(1, saved.getVersion());
        assertTrue(saved.getActive());
        assertEquals(1, saved.getHitCount());
        assertEquals(1, saved.getSuccessCount());
        assertEquals(accountId, saved.getAccountId());
        assertEquals(topologyJson, saved.getTopologyJson());
    }

    @Test
    void t1_shouldCreateChatMode_whenLevel1() {
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        TaskFeatures chatFeatures = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.Q_A,
                TaskFeatures.Domain.GENERAL,
                TaskFeatures.OutputType.SHORT_TEXT);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(chatFeatures)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);

        verify(patternRepository).save(patternCaptor.capture());
        assertEquals("CHAT", patternCaptor.getValue().getExecutionMode());
    }

    @Test
    void t1_shouldCreateSingleAgentMode_whenLevel2() {
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        TaskFeatures singleFeatures = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_2,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(singleFeatures)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);

        verify(patternRepository).save(patternCaptor.capture());
        assertEquals("SINGLE_AGENT", patternCaptor.getValue().getExecutionMode());
    }

    @Test
    void t1_shouldSkip_whenOutcomeNotSuccess() {
        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(failure)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t1_shouldSkip_whenSourceNotTriggerLlm() {
        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_MANUAL.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t1_shouldSkip_whenNoActualFeatures() {
        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(null)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t1_shouldSkip_whenDuplicateDetected() {
        InstinctPattern recentPattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(PatternFeatureVector.fromTaskFeatures(features))
                .build();
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of(recentPattern));

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t1_shouldNotSkip_whenDuplicateHasDifferentFeatures() {
        TaskFeatures differentFeatures = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.Q_A,
                TaskFeatures.Domain.CREATIVE,
                TaskFeatures.OutputType.SHORT_TEXT);

        InstinctPattern recentPattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(PatternFeatureVector.fromTaskFeatures(differentFeatures))
                .build();
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of(recentPattern));

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository).save(any());
    }

    @Test
    void t2_shouldCreateNewVersion_whenDeviationDetected() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern oldPattern = InstinctPattern.builder()
                .patternId(patternId)
                .accountId(accountId)
                .featureVector(PatternFeatureVector.fromTaskFeatures(features))
                .executionMode("SQUAD")
                .version(1)
                .active(true)
                .build();

        when(patternRepository.findById(patternId)).thenReturn(Optional.of(oldPattern));
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        FeatureDeviation deviation = new FeatureDeviation(
                true, 0.65, 0.85, List.of("dependency", "outputType"), null);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(patternId)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .deviation(deviation)
                .build();

        service.processFeedback(record, topologyJson);

        verify(patternRepository).save(patternCaptor.capture());
        InstinctPattern saved = patternCaptor.getValue();
        assertEquals(2, saved.getVersion());
        assertEquals(patternId, saved.getSourcePatternId());
        assertTrue(saved.getActive());
        assertEquals(topologyJson, saved.getTopologyJson());
    }

    @Test
    void t2_shouldSkip_whenNoDeviation() {
        UUID patternId = UUID.randomUUID();

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(patternId)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .deviation(null)
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t2_shouldSkip_whenMismatchDimensionsEmpty() {
        UUID patternId = UUID.randomUUID();

        FeatureDeviation emptyDeviation = new FeatureDeviation(
                false, 0.9, 0.9, List.of(), null);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(patternId)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .deviation(emptyDeviation)
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t2_shouldSkip_whenPatternNotFound() {
        UUID patternId = UUID.randomUUID();
        when(patternRepository.findById(patternId)).thenReturn(Optional.empty());

        FeatureDeviation deviation = new FeatureDeviation(
                true, 0.65, 0.85, List.of("dependency"), null);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(patternId)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .deviation(deviation)
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t2_shouldSkip_whenDuplicateDetected() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern oldPattern = InstinctPattern.builder()
                .patternId(patternId)
                .accountId(accountId)
                .featureVector(PatternFeatureVector.fromTaskFeatures(features))
                .version(1)
                .active(true)
                .build();

        InstinctPattern recentPattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(PatternFeatureVector.fromTaskFeatures(features))
                .build();

        when(patternRepository.findById(patternId)).thenReturn(Optional.of(oldPattern));
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of(recentPattern));

        FeatureDeviation deviation = new FeatureDeviation(
                true, 0.65, 0.85, List.of("dependency"), null);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(patternId)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .deviation(deviation)
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t2_shouldMergeFeatureVector_whenUpdating() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern oldPattern = InstinctPattern.builder()
                .patternId(patternId)
                .accountId(accountId)
                .featureVector(PatternFeatureVector.fromTaskFeatures(features))
                .version(1)
                .active(true)
                .build();

        when(patternRepository.findById(patternId)).thenReturn(Optional.of(oldPattern));
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        FeatureDeviation deviation = new FeatureDeviation(
                true, 0.65, 0.85, List.of("dependency"), null);

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(patternId)
                .accountId(accountId)
                .actualFeatures(features)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .deviation(deviation)
                .build();

        service.processFeedback(record, topologyJson);

        verify(patternRepository).save(patternCaptor.capture());
        InstinctPattern saved = patternCaptor.getValue();
        assertNotNull(saved.getFeatureVector());
        double score = saved.getFeatureVector().matchScore(features);
        assertTrue(score > 0.9, "Merged vector should still match original features");
    }

    @Test
    void processFeedback_shouldHandleNullFeaturesGracefully() {
        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(null)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void processFeedback_shouldHandleNullPatternId_withoutMatch() {
        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(null)
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);
        verify(patternRepository, never()).save(any());
    }

    @Test
    void t1_shouldBuildFeatureVectorFromActualFeatures() {
        when(patternRepository.findByAccountIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        PatternFeedbackRecord record = PatternFeedbackRecord.builder()
                .patternId(null)
                .accountId(accountId)
                .actualFeatures(features)
                .taskDescription("写一个排序算法")
                .outcome(success)
                .source(PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name())
                .build();

        service.processFeedback(record, topologyJson);

        verify(patternRepository).save(patternCaptor.capture());
        InstinctPattern saved = patternCaptor.getValue();
        assertNotNull(saved.getFeatureVector());
        assertEquals(1.0, saved.getFeatureVector().matchScore(features), 0.001);
    }
}
