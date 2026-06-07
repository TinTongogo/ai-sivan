package com.icusu.sivan.web.goal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 追加 Task 请求 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendTaskRequest {
    private List<String> descriptions;
}
