package com.icusu.sivan.application.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 连接测试结果。
 */
@Data
@Builder
@AllArgsConstructor
public class ConnectionTestResult {
    private boolean success;
    private String message;
}
