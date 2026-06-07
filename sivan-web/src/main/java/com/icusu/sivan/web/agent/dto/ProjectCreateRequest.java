package com.icusu.sivan.web.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建项目请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCreateRequest {
    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称最长 128 个字符")
    private String name;
}
