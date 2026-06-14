package com.icusu.sivan.application.memory.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

/**
 * 创建记忆请求 DTO。
 */
@Data
public class CreateMemoryRequest {
    private String level;
    private String scopeId;

    @NotBlank(message = "记忆内容不能为空")
    private String content;

    private String summary;
    private UUID projectId;
    private Boolean important;
}
