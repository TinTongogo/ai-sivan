package com.icusu.sivan.web.orchestration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 编排阶段节点响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class PhaseNodeResponse {
    private Integer phase;
    private String name;
    private String mode;
    private List<String> agents;
    private String description;
    private String inputFilter;
    private String outputFilter;
    private String hitlMode;
    private List<String> hitlAgents;
}
