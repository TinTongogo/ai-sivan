package com.icusu.sivan.infra.memory.curve;

import com.icusu.sivan.domain.forest.port.ForestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForgettingCurveServiceTest {

    @Mock
    private ForestRepository forestRepository;

    private ForgettingCurveService service;

    @BeforeEach
    void setUp() {
        service = new ForgettingCurveService(forestRepository);
    }

    @Test
    void autoArchive_shouldScanAllNonArchived() {
        when(forestRepository.countActiveMemories(null)).thenReturn(0L);
        service.autoArchive();
        verify(forestRepository).countActiveMemories(null);
    }
}
