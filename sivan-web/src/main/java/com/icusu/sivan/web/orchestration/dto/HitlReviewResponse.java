package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * HITL 审核记录响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class HitlReviewResponse {
    private UUID reviewId;
    private UUID executionId;
    private Integer phase;
    private String phaseName;
    private String inputContent;
    private String outputContent;
    private String humanFeedback;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
