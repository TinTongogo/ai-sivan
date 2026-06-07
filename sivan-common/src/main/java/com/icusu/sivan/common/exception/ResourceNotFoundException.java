package com.icusu.sivan.common.exception;

/**
 * 资源不存在异常。
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super(404, resource + " 不存在: " + id);
    }

    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}
