package com.icusu.sivan.web.goal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** 创建目标请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateGoalRequest {
    private String title;
    private String description;
    private UUID projectId;
    private UUID conversationId;
    @Builder.Default
    private String autoMode = "AUTO";
}
