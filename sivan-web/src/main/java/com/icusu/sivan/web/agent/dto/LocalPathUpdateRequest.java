package com.icusu.sivan.web.agent.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新项目本地路径请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocalPathUpdateRequest {
    @Size(max = 512, message = "路径最长 512 个字符")
    private String localPath;
}
