package com.icusu.sivan.application.knowledge.dto;

import lombok.Data;

import java.util.UUID;

/**
 * 更新知识库请求 DTO。
 */
@Data
public class UpdateKnowledgeBaseRequest {
    private String description;
    private UUID projectId;
}
