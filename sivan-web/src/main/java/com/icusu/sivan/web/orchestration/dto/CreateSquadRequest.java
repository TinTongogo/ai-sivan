package com.icusu.sivan.web.orchestration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 创建 Squad 请求 DTO。
 */
@Data
public class CreateSquadRequest {
    @NotBlank(message = "Squad 名称不能为空")
    @Size(max = 128)
    private String name;

    private String description;
    private String mode;
    private List<PhaseNodeRequest> phases;
    private UUID projectId;
}
