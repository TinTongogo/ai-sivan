package com.icusu.sivan.application.account.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新用户画像请求 DTO。
 */
@Data
public class UpdateUserProfileRequest {
    @Size(max = 64, message = "名称最长 64 个字符")
    private String name;

    @Size(max = 512, message = "简介最长 512 个字符")
    private String bio;

    @Size(max = 32, message = "AI 语言设置最长 32 个字符")
    private String aiLanguage;

    @Size(max = 20, message = "专长标签最多 20 个")
    private List<String> expertise;

    private Boolean autoLearn;
}
