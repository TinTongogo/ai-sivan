package com.icusu.sivan.domain.security;

/**
 * 策略违反异常 — 动作被安全策略拒绝时抛出。
 */
public class PolicyViolationException extends RuntimeException {
    public PolicyViolationException(String message) {
        super(message);
    }
}
