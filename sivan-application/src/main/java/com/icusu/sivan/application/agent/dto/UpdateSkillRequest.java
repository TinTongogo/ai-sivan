package com.icusu.sivan.application.agent.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 更新技能请求 DTO。
 */
@Data
public class UpdateSkillRequest {

    private String name;
    private String displayName;
    private String description;
    private String content;
    private String category;
    private List<String> tags;
    private UUID projectId;
    private String status;
}
