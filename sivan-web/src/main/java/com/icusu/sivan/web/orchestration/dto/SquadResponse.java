package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Squad 响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class SquadResponse {
    private UUID squadId;
    private UUID projectId;
    private String name;
    private String description;
    private String mode;
    private String source;
    private Boolean active;
    private List<PhaseNodeResponse> phases;
    private Integer usageCount;
    private Double successRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
