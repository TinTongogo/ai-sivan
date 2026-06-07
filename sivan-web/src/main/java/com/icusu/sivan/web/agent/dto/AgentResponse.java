package com.icusu.sivan.web.agent.dto;

import com.icusu.sivan.domain.tool.ToolRequirement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 智能体响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class AgentResponse {

    private UUID agentId;
    private String agentName;
    private String displayName;
    private String description;
    private String category;
    private String systemPrompt;
    private String craftDeclaration;
    private List<String> skillIds;
    private ToolRequirement toolRequirements;
    private String agentType;
    private String status;
    private Integer version;
    private Integer usageCount;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
