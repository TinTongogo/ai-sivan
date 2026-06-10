package com.icusu.sivan.domain.memory.flashback;

import com.icusu.sivan.common.enums.MemoryLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 情境闪现候选条目。
 */
@Data
@Builder
@AllArgsConstructor
public class FlashbackCandidate {
    private UUID memoryId;
    private String content;
    private MemoryLevel level;
    private double relevanceScore;
    private double retention;
    private int accessCount;
    private boolean important;
    private LocalDateTime lastAccessedAt;
}
