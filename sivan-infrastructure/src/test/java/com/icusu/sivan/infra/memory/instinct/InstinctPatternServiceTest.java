package com.icusu.sivan.infra.memory.instinct;

import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.SharedTemplate;
import com.icusu.sivan.domain.task.PatternFeatureVector;
import com.icusu.sivan.domain.task.TaskFeatures;
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
class InstinctPatternServiceTest {

    @Mock
    private IInstinctPatternRepository patternRepository;
    @Mock
    private ISharedTemplateRepository sharedTemplateRepository;

    @Captor
    private ArgumentCaptor<InstinctPattern> patternCaptor;

    private InstinctPatternService service;
    private final UUID accountId = UUID.randomUUID();

    private TaskFeatures codingFeatures;
    private TaskFeatures writingFeatures;
    private PatternFeatureVector codingVector;

    @BeforeEach
    void setUp() {
        service = new InstinctPatternService(patternRepository, sharedTemplateRepository);
        codingFeatures = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3,
                TaskFeatures.Dependency.SEQUENTIAL,
                TaskFeatures.InputStructure.CODE,
                TaskFeatures.Domain.CODING,
                TaskFeatures.OutputType.CODE
        );
        writingFeatures = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_2,
                TaskFeatures.Dependency.INDEPENDENT,
                TaskFeatures.InputStructure.FREE_TEXT,
                TaskFeatures.Domain.WRITING,
                TaskFeatures.OutputType.LONG_TEXT
        );
        codingVector = PatternFeatureVector.fromTaskFeatures(codingFeatures);
    }

    private InstinctPattern createCodingPattern() {
        return InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .accountId(accountId)
                .featureVector(codingVector)
                .executionMode("SQUAD")
                .active(true)
                .build();
    }

    private SharedTemplate createSharedTemplate(UUID patternId, String quality) {
        return SharedTemplate.builder()
                .templateId(UUID.randomUUID())
                .patternId(patternId)
                .quality(quality)
                .build();
    }

    @Test
    void freeze_shouldCreateNew() {
        InstinctPattern result = service.freeze(accountId, "{\"phases\":[]}", codingVector, "SQUAD");
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getTotalCount());
        assertEquals("SQUAD", result.getExecutionMode());
        assertEquals(1, result.getVersion());
        assertFalse(result.getActive());
        verify(patternRepository).save(any());
    }

    @Test
    void match_shouldReturnEmpty_whenNoActivePatterns() {
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of());
        TaskFeatures features = new TaskFeatures(TaskFeatures.Complexity.LEVEL_2, TaskFeatures.Dependency.INDEPENDENT, TaskFeatures.InputStructure.FREE_TEXT, TaskFeatures.Domain.GENERAL, TaskFeatures.OutputType.SHORT_TEXT);
        assertTrue(service.match(features, accountId).isEmpty());
    }

    @Test
    void match_shouldReturnBestPattern_whenAboveThreshold() {
        PatternFeatureVector vector = PatternFeatureVector.fromTaskFeatures(
                new TaskFeatures(TaskFeatures.Complexity.LEVEL_2, TaskFeatures.Dependency.INDEPENDENT, TaskFeatures.InputStructure.FREE_TEXT, TaskFeatures.Domain.GENERAL, TaskFeatures.OutputType.SHORT_TEXT));
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(vector)
                .successCount(5)
                .totalCount(6)
                .active(true)
                .build();
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(pattern));

        TaskFeatures features = new TaskFeatures(TaskFeatures.Complexity.LEVEL_2, TaskFeatures.Dependency.INDEPENDENT, TaskFeatures.InputStructure.FREE_TEXT, TaskFeatures.Domain.GENERAL, TaskFeatures.OutputType.SHORT_TEXT);
        Optional<InstinctPattern> result = service.match(features, accountId);
        assertTrue(result.isPresent());
        assertEquals(pattern.getPatternId(), result.get().getPatternId());
    }

    @Test
    void match_shouldSkipPatternsWithoutFeatureVector() {
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(null)
                .successCount(5)
                .totalCount(6)
                .active(true)
                .build();
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(pattern));

        TaskFeatures features = new TaskFeatures(TaskFeatures.Complexity.LEVEL_2, TaskFeatures.Dependency.INDEPENDENT, TaskFeatures.InputStructure.FREE_TEXT, TaskFeatures.Domain.GENERAL, TaskFeatures.OutputType.SHORT_TEXT);
        Optional<InstinctPattern> result = service.match(features, accountId);
        assertTrue(result.isEmpty());
    }

    @Test
    void recordResult_shouldUpdateCounts() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(patternId)
                .successCount(2)
                .totalCount(3)
                .active(false)
                .build();
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(pattern));

        service.recordResult(patternId, true);

        assertEquals(4, pattern.getTotalCount());
        assertEquals(3, pattern.getSuccessCount());
        verify(patternRepository).update(pattern);
    }

    @Test
    void recordResult_shouldAutoActivate_whenSuccessRateAbove75Percent() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(patternId)
                .successCount(2)
                .totalCount(3)
                .active(false)
                .build();
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(pattern));

        service.recordResult(patternId, true);

        assertTrue(pattern.getActive());
    }

    @Test
    void recordResult_shouldNotAutoActivate_below75Percent() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(patternId)
                .successCount(2)
                .totalCount(4)
                .active(false)
                .build();
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(pattern));

        service.recordResult(patternId, true);

        assertFalse(pattern.getActive());
    }

    @Test
    void recordResult_shouldHandleNullTotalAndSuccess() {
        UUID patternId = UUID.randomUUID();
        InstinctPattern pattern = InstinctPattern.builder()
                .patternId(patternId)
                .successCount(null)
                .totalCount(null)
                .active(false)
                .build();
        when(patternRepository.findById(patternId)).thenReturn(Optional.of(pattern));

        service.recordResult(patternId, false);

        assertEquals(1, pattern.getTotalCount());
        assertEquals(0, pattern.getSuccessCount());
        verify(patternRepository).update(pattern);
    }

    @Test
    void recordResult_shouldDoNothing_whenNotFound() {
        UUID patternId = UUID.randomUUID();
        when(patternRepository.findById(patternId)).thenReturn(Optional.empty());

        service.recordResult(patternId, true);

        verify(patternRepository, never()).update(any());
    }

    @Test
    void matchFeatures_shouldUseOwnPattern_whenHit() {
        InstinctPattern own = createCodingPattern();
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of(own));
        when(patternRepository.findById(own.getPatternId())).thenReturn(Optional.of(own));

        Optional<InstinctPattern> result = service.match(codingFeatures, accountId);

        assertTrue(result.isPresent());
        assertEquals(own.getPatternId(), result.get().getPatternId());
        verify(sharedTemplateRepository, never()).findByVisibilityAndNotOwner(any(), any());
    }

    @Test
    void matchFeatures_shouldFallbackToShared_whenOwnMiss() {
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of());

        InstinctPattern shared = createCodingPattern();
        when(patternRepository.findById(shared.getPatternId())).thenReturn(Optional.of(shared));
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId))
                .thenReturn(List.of(createSharedTemplate(shared.getPatternId(), "NORMAL")));
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId))
                .thenReturn(List.of());
        when(sharedTemplateRepository.findByAllowedAccount(accountId))
                .thenReturn(List.of());

        Optional<InstinctPattern> result = service.match(codingFeatures, accountId);

        assertTrue(result.isPresent());
        assertEquals(shared.getPatternId(), result.get().getSourcePatternId());
        verify(patternRepository).save(any());
    }

    @Test
    void matchFeatures_shouldReturnEmpty_whenBothOwnAndSharedMiss() {
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of());
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId)).thenReturn(List.of());
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId)).thenReturn(List.of());
        when(sharedTemplateRepository.findByAllowedAccount(accountId)).thenReturn(List.of());

        assertTrue(service.match(writingFeatures, accountId).isEmpty());
        verify(patternRepository, never()).save(any());
    }

    @Test
    void matchFeatures_shouldPreferNormalOverLowQuality() {
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of());

        InstinctPattern lowQualityPattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(codingVector)
                .active(true)
                .build();
        PatternFeatureVector nearCodingVector = PatternFeatureVector.fromTaskFeatures(
                new TaskFeatures(TaskFeatures.Complexity.LEVEL_3, TaskFeatures.Dependency.SEQUENTIAL,
                        TaskFeatures.InputStructure.CODE, TaskFeatures.Domain.CODING,
                        TaskFeatures.OutputType.JSON));
        InstinctPattern normalPattern = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .featureVector(nearCodingVector)
                .active(true)
                .build();

        when(patternRepository.findById(lowQualityPattern.getPatternId()))
                .thenReturn(Optional.of(lowQualityPattern));
        when(patternRepository.findById(normalPattern.getPatternId()))
                .thenReturn(Optional.of(normalPattern));
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId))
                .thenReturn(List.of(
                        createSharedTemplate(lowQualityPattern.getPatternId(), "LOW_QUALITY"),
                        createSharedTemplate(normalPattern.getPatternId(), "NORMAL")
                ));
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId)).thenReturn(List.of());
        when(sharedTemplateRepository.findByAllowedAccount(accountId)).thenReturn(List.of());

        Optional<InstinctPattern> result = service.match(codingFeatures, accountId);

        assertTrue(result.isPresent());
        assertEquals(normalPattern.getPatternId(), result.get().getSourcePatternId());
    }

    @Test
    void matchFeatures_shouldSkipSharedTemplate_whenPatternNotFound() {
        when(patternRepository.findActiveByAccount(accountId)).thenReturn(List.of());

        UUID missingPatternId = UUID.randomUUID();
        when(patternRepository.findById(missingPatternId)).thenReturn(Optional.empty());
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId))
                .thenReturn(List.of(createSharedTemplate(missingPatternId, "NORMAL")));
        when(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId)).thenReturn(List.of());
        when(sharedTemplateRepository.findByAllowedAccount(accountId)).thenReturn(List.of());

        assertTrue(service.match(codingFeatures, accountId).isEmpty());
    }
}
