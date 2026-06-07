package com.icusu.sivan.memory.pattern;

import com.icusu.sivan.domain.memory.IExplorationStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExplorationDeciderTest {

    final UUID accountId = UUID.randomUUID();
    ExplorationDecider decider;

    @Mock
    IExplorationStateRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        decider = new ExplorationDecider(repository);
    }

    @Test
    void shouldReturnBaseRateWhenNoTemplates() {
        double rate = decider.computeRate(0);
        assertEquals(0.10, rate, 0.001);
    }

    @Test
    void shouldDecayRateEvery10Templates() {
        assertEquals(0.10, decider.computeRate(0), 0.001);
        assertEquals(0.09, decider.computeRate(10), 0.001);
        assertEquals(0.08, decider.computeRate(20), 0.001);
        assertEquals(0.04, decider.computeRate(60), 0.001);
    }

    @Test
    void shouldFloorAtMinRate() {
        double rate = decider.computeRate(100);
        assertEquals(0.03, rate, 0.001);
    }

    @Test
    void shouldNotGoBelowMinRate() {
        double rate = decider.computeRate(200);
        assertEquals(0.03, rate, 0.001);
    }

    @Test
    void shouldExploreWithinExpectedRange() {
        decider.reset(accountId);
        int explores = 0;
        int trials = 10000;
        for (int i = 0; i < trials; i++) {
            if (decider.shouldExplore(accountId, 0)) {
                explores++;
            }
        }
        // 10% rate →  between 8% and 12% with high probability
        double rate = (double) explores / trials;
        assertTrue(rate > 0.07 && rate < 0.13,
                "探索率应该在 7%~13% 之间，实际=" + String.format("%.3f", rate));
    }

    @Test
    void shouldResetState() {
        decider.shouldExplore(accountId, 0);
        decider.reset(accountId);
        // After reset, state is fresh
        assertDoesNotThrow(() -> decider.shouldExplore(accountId, 0));
    }
}
