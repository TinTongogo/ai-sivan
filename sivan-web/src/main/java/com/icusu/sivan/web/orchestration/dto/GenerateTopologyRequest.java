package com.icusu.sivan.web.orchestration.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 生成拓扑结构请求 DTO。
 */
@Data
public class GenerateTopologyRequest {
    @NotBlank(message = "任务描述不能为空")
    private String taskDescription;
}
