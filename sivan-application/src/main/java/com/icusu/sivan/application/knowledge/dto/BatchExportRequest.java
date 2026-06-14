package com.icusu.sivan.application.knowledge.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 批量导出文档请求 DTO。
 */
@Data
public class BatchExportRequest {

    @NotEmpty(message = "文档 ID 列表不能为空")
    private List<UUID> docIds;
}
