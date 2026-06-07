package com.icusu.sivan.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应格式。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaseResponse<T> {

    private int code;
    private String message;
    private T data;

    /**
     * 返回成功响应。
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(200, "success", data);
    }

    /**
     * 返回空成功响应。
     */
    public static <T> BaseResponse<T> success() {
        return empty(200, "success");
    }

    /**
     * 返回创建成功响应。
     */
    public static <T> BaseResponse<T> created(T data) {
        return new BaseResponse<>(201, "created", data);
    }

    /**
     * 返回已接受响应。
     */
    public static <T> BaseResponse<T> accepted(T data) {
        return new BaseResponse<>(202, "accepted", data);
    }

    /**
     * 返回错误响应。
     */
    public static <T> BaseResponse<T> error(int code, String message) {
        return empty(code, message);
    }

    /**
     * 返回 400 错误响应。
     */
    public static <T> BaseResponse<T> badRequest(String message) {
        return empty(400, message);
    }

    /**
     * 返回 401 错误响应。
     */
    public static <T> BaseResponse<T> unauthorized(String message) {
        return empty(401, message);
    }

    /**
     * 返回 403 错误响应。
     */
    public static <T> BaseResponse<T> forbidden(String message) {
        return empty(403, message);
    }

    /**
     * 返回 404 错误响应。
     */
    public static <T> BaseResponse<T> notFound(String message) {
        return empty(404, message);
    }

    /**
     * 返回 500 错误响应。
     */
    public static <T> BaseResponse<T> internalError(String message) {
        return empty(500, message);
    }

    /** 创建空 data 的响应，仅一处抑制 unchecked 警告。 */
    @SuppressWarnings("unchecked")
    private static <T> BaseResponse<T> empty(int code, String message) {
        return (BaseResponse<T>) new BaseResponse<Void>(code, message, null);
    }
}
