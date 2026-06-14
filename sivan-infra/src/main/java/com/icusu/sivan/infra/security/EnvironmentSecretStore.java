package com.icusu.sivan.infra.security;

import com.icusu.sivan.domain.security.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 环境变量密钥存储 — v2.0 默认实现。
 * <p>
 * 从环境变量读取密钥，不支持写操作。
 * 生产环境可替换为 Vault / AWS Secrets Manager。
 */
@Component
@ConditionalOnMissingBean(SecretStore.class)
public class EnvironmentSecretStore implements SecretStore {

    @Override
    public String get(String key) {
        return Optional.ofNullable(System.getenv(key))
                .orElseThrow(() -> new RuntimeException("密钥未配置: " + key));
    }

    @Override
    public void set(String key, String value) {
        throw new UnsupportedOperationException("EnvironmentSecretStore 不支持写操作");
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException("EnvironmentSecretStore 不支持删除");
    }

    @Override
    public boolean exists(String key) {
        return System.getenv(key) != null;
    }
}
