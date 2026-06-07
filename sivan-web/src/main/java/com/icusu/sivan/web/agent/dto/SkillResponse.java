package com.icusu.sivan.web.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 技能响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class SkillResponse {

    private UUID skillId;
    private String skillType;
    private String skillCode;
    private String name;
    private String displayName;
    private String description;
    private String content;
    private String category;
    private List<String> tags;
    private Integer usageCount;
    private LocalDateTime lastUsedAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
