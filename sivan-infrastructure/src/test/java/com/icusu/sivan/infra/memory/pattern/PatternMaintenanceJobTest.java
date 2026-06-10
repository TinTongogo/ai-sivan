package com.icusu.sivan.infra.memory.pattern;

import com.icusu.sivan.domain.feedback.IPatternFeedbackRepository;
import com.icusu.sivan.domain.feedback.PatternFeedbackRecord;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatternMaintenanceJobTest {

    @Mock
    private IInstinctPatternRepository patternRepository;
    @Mock
    private IPatternFeedbackRepository feedbackRepository;

    private PatternMaintenanceJob job;

    @BeforeEach
    void setUp() {
        job = new PatternMaintenanceJob(patternRepository, feedbackRepository);
    }

    @Test
    void t3_shouldDegrade_whenSuccessRateBelow30PercentAndSamplesEnough() {
        InstinctPattern failing = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .successCount(1)
                .totalCount(5)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(failing));

        int count = job.degradeFailingPatterns();

        assertEquals(1, count);
        assertFalse(failing.getActive());
        verify(patternRepository).update(failing);
    }

    @Test
    void t3_shouldNotDegrade_whenSuccessRateAbove30Percent() {
        InstinctPattern ok = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .successCount(2)
                .totalCount(5)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(ok));

        int count = job.degradeFailingPatterns();

        assertEquals(0, count);
        assertTrue(ok.getActive());
        verify(patternRepository, never()).update(any());
    }

    @Test
    void t3_shouldNotDegrade_whenInsufficientSamples() {
        InstinctPattern fewSamples = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .successCount(0)
                .totalCount(3)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(fewSamples));

        int count = job.degradeFailingPatterns();

        assertEquals(0, count);
        verify(patternRepository, never()).update(any());
    }

    @Test
    void t3_shouldNotDegrade_whenExactlyAtThreshold() {
        InstinctPattern borderline = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .successCount(1)
                .totalCount(3)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(borderline));

        int count = job.degradeFailingPatterns();

        assertEquals(0, count);
    }

    @Test
    void t3_shouldHandleNullSuccessAndTotal() {
        InstinctPattern nullCounts = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .successCount(null)
                .totalCount(null)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(nullCounts));

        int count = job.degradeFailingPatterns();

        assertEquals(0, count);
        verify(patternRepository, never()).update(any());
    }

    @Test
    void t4_shouldArchive_whenLastMatchExceeds30DaysAndHitCountLow() {
        InstinctPattern cold = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .lastMatchAt(LocalDateTime.now().minusDays(40))
                .hitCount(2)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(cold));

        int count = job.archiveColdPatterns();

        assertEquals(1, count);
        assertFalse(cold.getActive());
        verify(patternRepository).update(cold);
    }

    @Test
    void t4_shouldNotArchive_whenLastMatchWithin30Days() {
        InstinctPattern recent = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .lastMatchAt(LocalDateTime.now().minusDays(10))
                .hitCount(1)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(recent));

        int count = job.archiveColdPatterns();

        assertEquals(0, count);
        assertTrue(recent.getActive());
        verify(patternRepository, never()).update(any());
    }

    @Test
    void t4_shouldNotArchive_whenHitCountAboveThreshold() {
        InstinctPattern popular = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .lastMatchAt(LocalDateTime.now().minusDays(40))
                .hitCount(5)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(popular));

        int count = job.archiveColdPatterns();

        assertEquals(0, count);
        verify(patternRepository, never()).update(any());
    }

    @Test
    void t4_shouldSkip_whenLastMatchAtIsNull() {
        InstinctPattern noMatch = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .lastMatchAt(null)
                .hitCount(0)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(noMatch));

        int count = job.archiveColdPatterns();

        assertEquals(0, count);
        verify(patternRepository, never()).update(any());
    }

    @Test
    void t4_shouldSkip_whenHitCountIsNull() {
        InstinctPattern nullHit = InstinctPattern.builder()
                .patternId(UUID.randomUUID())
                .lastMatchAt(LocalDateTime.now().minusDays(40))
                .hitCount(null)
                .active(true)
                .build();
        when(patternRepository.findAllActive()).thenReturn(List.of(nullHit));

        int count = job.archiveColdPatterns();

        assertEquals(0, count);
        verify(patternRepository, never()).update(any());
    }

    @Test
    void cleanupExpiredFeedback_shouldDeleteOldRecords() {
        PatternFeedbackRecord oldRecord = PatternFeedbackRecord.builder()
                .feedbackId(UUID.randomUUID())
                .build();
        when(feedbackRepository.findByCreatedAtBefore(any())).thenReturn(List.of(oldRecord));

        int count = job.cleanupExpiredFeedback();

        assertEquals(1, count);
        verify(feedbackRepository).deleteByFeedbackId(oldRecord.getFeedbackId());
    }

    @Test
    void cleanupExpiredFeedback_shouldDoNothing_whenNoExpiredRecords() {
        when(feedbackRepository.findByCreatedAtBefore(any())).thenReturn(List.of());

        int count = job.cleanupExpiredFeedback();

        assertEquals(0, count);
        verify(feedbackRepository, never()).deleteByFeedbackId(any());
    }

    @Test
    void dailyMaintenance_shouldExecuteAllThreeSteps() {
        when(patternRepository.findAllActive()).thenReturn(List.of());
        when(feedbackRepository.findByCreatedAtBefore(any())).thenReturn(List.of());

        job.dailyMaintenance();

        verify(patternRepository, atLeastOnce()).findAllActive();
        verify(feedbackRepository).findByCreatedAtBefore(any());
    }
}
