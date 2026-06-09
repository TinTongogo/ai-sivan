package com.icusu.sivan.common.exception;

import lombok.Getter;

/**
 * 领域异常基类，所有业务异常继承此类。
 * <p>
 * 支持 i18n：设置 {@code messageCode} 后，{@code GlobalExceptionHandler} 会通过
 * {@code MessageSource} 解析本地化消息。{@code messageCode == null} 时保持向后兼容，
 * 直接使用 {@link #getMessage()}。
 */
@Getter
public class DomainException extends RuntimeException {

    private final int code;
    private final String messageCode;
    private final Object[] messageArgs;

    public DomainException(int code, String message) {
        super(message);
        this.code = code;
        this.messageCode = null;
        this.messageArgs = null;
    }

    public DomainException(String message) {
        super(message);
        this.code = 400;
        this.messageCode = null;
        this.messageArgs = null;
    }

    public DomainException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.messageCode = null;
        this.messageArgs = null;
    }

    /** i18n 构造器：指定 messageCode，由 MessageSource 解析本地化文本。 */
    public DomainException(int code, String messageCode, Object... messageArgs) {
        super(messageCode);
        this.code = code;
        this.messageCode = messageCode;
        this.messageArgs = messageArgs;
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
