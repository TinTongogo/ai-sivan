package com.icusu.sivan.infra.memory.curve;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.curve.EbbinghausForgettingCurve;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EbbinghausForgettingCurveTest {

    @Test
    void calculateRetentionWithAccess_moreAccess_slowerDecay() {
        LocalDateTime accessed = LocalDateTime.now().minusHours(48);
        double once = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.USER, accessed, 1, LocalDateTime.now());
        double ten = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.USER, accessed, 10, LocalDateTime.now());
        assertTrue(once < ten, "More accesses should yield higher retention");
    }

    @Test
    void calculateRetentionWithAccess_diffLevels_diffCompensation() {
        LocalDateTime accessed = LocalDateTime.now().minusHours(48);
        double user = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.USER, accessed, 10, LocalDateTime.now());
        double session = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.SESSION, accessed, 10, LocalDateTime.now());
        assertTrue(session < user, "SESSION should decay faster than USER even with reinforcement");
    }
}
