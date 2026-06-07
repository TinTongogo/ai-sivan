package com.icusu.sivan.web.orchestration.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Squad 执行请求 DTO。
 */
@Data
public class ExecuteSquadRequest {

    @NotBlank(message = "任务描述不能为空")
    private String taskDescription;
}
