package com.icusu.sivan.common.exception;

import lombok.Getter;

/**
 * 领域异常基类，所有业务异常继承此类。
 */
@Getter
public class DomainException extends RuntimeException {

    private final int code;

    public DomainException(int code, String message) {
        super(message);
        this.code = code;
    }

    public DomainException(String message) {
        super(message);
        this.code = 400;
    }

    public DomainException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    // ---- 常用工厂方法 ----

    /**
     * 创建资源不存在异常。
     */
    public static DomainException notFound(String resource, Object id) {
        return new DomainException(404, resource + " 不存在: " + id);
    }

    /**
     * 创建冲突异常。
     */
    public static DomainException conflict(String message) {
        return new DomainException(409, message);
    }

    /**
     * 创建权限不足异常。
     */
    public static DomainException forbidden(String message) {
        return new DomainException(403, message);
    }

    /**
     * 创建请求参数错误异常。
     */
    public static DomainException badRequest(String message) {
        return new DomainException(400, message);
    }
}
