package com.icusu.sivan.memory.curve;

import com.icusu.sivan.common.enums.MemoryLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EbbinghausForgettingCurveTest {

    /** 更多访问次数应带来更高的保留率。 */
    @Test
    void calculateRetentionWithAccess_moreAccess_slowerDecay() {
        LocalDateTime accessed = LocalDateTime.now().minusHours(48);
        double once = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.USER, accessed, 1, LocalDateTime.now());
        double ten = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.USER, accessed, 10, LocalDateTime.now());
        assertTrue(once < ten, "More accesses should yield higher retention");
    }

    /** 不同记忆级别的强化补偿效果不同。 */
    @Test
    void calculateRetentionWithAccess_diffLevels_diffCompensation() {
        LocalDateTime accessed = LocalDateTime.now().minusHours(48);
        double user = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.USER, accessed, 10, LocalDateTime.now());
        double session = EbbinghausForgettingCurve.calculateRetentionWithAccess(MemoryLevel.SESSION, accessed, 10, LocalDateTime.now());
        assertTrue(session < user, "SESSION should decay faster than USER even with reinforcement");
    }
}
