package com.icusu.sivan.application.agent.dto;

import com.icusu.sivan.domain.tool.ToolRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建智能体请求 DTO。
 */
@Data
public class CreateAgentRequest {

    @NotBlank(message = "智能体名称不能为空")
    @Size(max = 64, message = "名称最长 64 个字符")
    private String agentName;

    @NotBlank(message = "显示名称不能为空")
    @Size(max = 128, message = "显示名称最长 128 个字符")
    private String displayName;

    @NotBlank(message = "智能体描述不能为空")
    @Size(max = 256, message = "描述最长 256 个字符")
    private String description;

    private String category;

    @NotBlank(message = "系统提示词不能为空")
    private String systemPrompt;

    private String craftDeclaration;

    private List<String> skillIds;
    private ToolRequirement toolRequirements;
}
