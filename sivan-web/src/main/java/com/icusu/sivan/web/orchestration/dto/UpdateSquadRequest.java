package com.icusu.sivan.web.orchestration.dto;

import lombok.Data;

import java.util.List;

/**
 * 更新 Squad 请求 DTO。
 */
@Data
public class UpdateSquadRequest {
    private String name;
    private String description;
    private String mode;
    private List<PhaseNodeRequest> phases;
    private Boolean active;
}
