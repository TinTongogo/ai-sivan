package com.icusu.sivan.application.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 批量移动文档请求 DTO。
 */
@Data
public class BatchMoveDocumentsRequest {

    @NotEmpty(message = "文档 ID 列表不能为空")
    private List<UUID> docIds;

    @NotBlank(message = "目标知识库名称不能为空")
    private String targetKbName;
}
