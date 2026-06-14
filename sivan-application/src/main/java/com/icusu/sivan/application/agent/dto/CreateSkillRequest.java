package com.icusu.sivan.application.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 创建技能请求 DTO。
 */
@Data
public class CreateSkillRequest {

    @NotBlank(message = "技能代码不能为空")
    @Size(max = 64, message = "技能代码最长 64 个字符")
    private String skillCode;

    @NotBlank(message = "技能名称不能为空")
    @Size(max = 128, message = "名称最长 128 个字符")
    private String name;

    private String displayName;
    private String description;
    private String content;
    private String category;
    private List<String> tags;
    private UUID projectId;
}
