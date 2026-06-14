package com.icusu.sivan.application.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 润色请求 DTO。
 */
@Data
public class PolishRequest {
    @NotBlank(message = "润色文本不能为空")
    private String text;
}
