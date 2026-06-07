package com.icusu.sivan.web.agent.dto;

import com.icusu.sivan.domain.tool.ToolRequirement;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 更新智能体请求 DTO。
 */
@Data
public class UpdateAgentRequest {

    private String displayName;
    private String description;
    private String category;
    private String systemPrompt;
    private String craftDeclaration;
    private List<String> skillIds;
    private ToolRequirement toolRequirements;
    private UUID projectId;
    private String status;
}
