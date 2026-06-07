package com.icusu.sivan.memory.curve;

import com.icusu.sivan.domain.memory.IMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForgettingCurveServiceTest {

    @Mock
    private IMemoryRepository memoryRepository;

    private ForgettingCurveService service;

    @BeforeEach
    void setUp() {
        service = new ForgettingCurveService(memoryRepository);
    }

    @Test
    void autoArchive_shouldScanAllNonArchived() {
        when(memoryRepository.findAllNonArchived()).thenReturn(List.of());
        service.autoArchive();
        verify(memoryRepository).findAllNonArchived();
    }
}
