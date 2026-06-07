package com.icusu.sivan.web.routing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建路由决策请求 DTO。
 */
@Data
public class CreateRoutingDecisionRequest {
    @NotBlank(message = "任务描述不能为空")
    private String taskDescription;

    private String strategy;
    private String selectedAgentName;
    private Boolean success;
    private Double confidence;
    private String reasoning;
}
