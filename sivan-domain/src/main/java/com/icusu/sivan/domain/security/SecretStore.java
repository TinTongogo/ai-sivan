package com.icusu.sivan.domain.security;

/** 密钥存储接口 — 领域层抽象，不依赖任何框架。 */
public interface SecretStore {
    String get(String key);
    void set(String key, String value);
    void delete(String key);
    boolean exists(String key);
}
