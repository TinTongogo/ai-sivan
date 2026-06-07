package com.icusu.sivan.common.exception;

/**
 * 业务规则冲突异常。
 */
public class BusinessException extends DomainException {

    public BusinessException(String message) {
        super(409, message);
    }

    public BusinessException(int code, String message) {
        super(code, message);
    }
}
