package com.icusu.sivan.common.exception;

/**
 * 未授权或权限不足异常。
 */
public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super(401, message);
    }
}
