package com.icusu.sivan.common.dto;

import java.time.Instant;

/**
 * 统一错误响应格式。
 * <p>
 * 所有异常通过 {@code @ExceptionHandler} 转换为此格式返回。
 */
public record ApiError(
        int code,
        String message,
        String detail,
        String path,
        Instant timestamp
) {
    public static final int GOAL_NOT_FOUND = 40401;
    public static final int GOAL_INVALID_STATE = 40001;
    public static final int VALIDATION_ERROR = 40002;
    public static final int TOOL_NOT_FOUND = 40402;
    public static final int MODEL_NOT_FOUND = 40403;
    public static final int POLICY_VIOLATION = 40301;
    public static final int RATE_LIMITED = 42901;
    public static final int INTERNAL_ERROR = 50000;
}
