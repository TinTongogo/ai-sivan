package com.icusu.sivan.application.account.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @Size(max = 64, message = "显示名称最长 64 个字符")
    private String displayName;

    @Size(max = 4096, message = "偏好设置最长 4096 个字符")
    private String preferences;

    @Size(max = 1024, message = "额度信息最长 1024 个字符")
    private String quota;
}
