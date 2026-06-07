package com.icusu.sivan.web.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 创建知识库请求 DTO。
 */
@Data
public class CreateKnowledgeBaseRequest {
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 128)
    private String kbName;

    private UUID projectId;

    private String description;
}
