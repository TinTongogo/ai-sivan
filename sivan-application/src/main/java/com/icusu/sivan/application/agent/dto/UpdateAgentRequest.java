package com.icusu.sivan.application.agent.dto;

import com.icusu.sivan.domain.tool.ToolRequirement;
import lombok.Data;

import java.util.List;

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
    private String status;
}
