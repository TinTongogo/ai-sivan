package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 契约响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class ContractResponse {

    private UUID contractId;
    private UUID executionId;
    private Integer phase;
    private String sourceAgent;
    private String targetAgent;
    private String content;
    private String contentType;
    private LocalDateTime createdAt;
}
