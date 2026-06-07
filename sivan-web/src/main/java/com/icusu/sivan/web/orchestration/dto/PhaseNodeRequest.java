package com.icusu.sivan.web.orchestration.dto;

import lombok.Data;

import java.util.List;

/**
 * 阶段节点请求 DTO，用于 Squad 各阶段配置。
 */
@Data
public class PhaseNodeRequest {
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
