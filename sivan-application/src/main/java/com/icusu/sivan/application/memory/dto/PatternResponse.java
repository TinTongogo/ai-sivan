package com.icusu.sivan.application.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 本能模板响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class PatternResponse {
    private UUID patternId;
    private UUID accountId;
    private String executionMode;
    private Integer hitCount;
    private Integer successCount;
    private Integer totalCount;
    private UUID sourcePatternId;
    private Integer version;
    private Boolean active;
    private Double modeSuccessRate;
    private LocalDateTime lastMatchAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
