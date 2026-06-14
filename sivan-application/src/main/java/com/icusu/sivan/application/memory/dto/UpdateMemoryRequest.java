package com.icusu.sivan.application.memory.dto;

import lombok.Data;

/**
 * 更新记忆请求 DTO。
 */
@Data
public class UpdateMemoryRequest {
    private String content;
    private String summary;
    private Boolean important;
    private Float retention;
    private Boolean archived;
}
