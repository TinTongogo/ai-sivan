package com.icusu.sivan.web.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 默认凭据启动守卫。
 * <p>检测 JWT secret / DB 密码是否为默认值，若是则拒绝启动，
 * 防止开发环境弱凭据上线。</p>
 */
@Slf4j
@Component
@Profile("prod")
public class DefaultCredentialGuard implements ApplicationRunner {

    private static final String DEFAULT_JWT_SECRET = "sivan-jwt-secret-key-change-in-production-minimum-256-bits";
    private static final String DEFAULT_DB_PASSWORD = "sivan";
    private static final String DEFAULT_MASTER_KEY = "dev-master-key-change-in-production";

    private final String jwtSecret;
    private final String dbPassword;
    private final String encryptionMasterKey;

    public DefaultCredentialGuard(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${spring.datasource.password}") String dbPassword,
            @Value("${sivan.encryption.master-key}") String encryptionMasterKey) {
        this.jwtSecret = jwtSecret;
        this.dbPassword = dbPassword;
        this.encryptionMasterKey = encryptionMasterKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasIssue = false;

        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            log.error("✕ JWT secret 为默认值，拒绝启动。通过环境变量 JWT_SECRET 设置");
            hasIssue = true;
        }
        if (DEFAULT_DB_PASSWORD.equals(dbPassword)) {
            log.error("✕ 数据库密码为默认值，拒绝启动。通过环境变量 DB_PASSWORD 设置");
            hasIssue = true;
        }
        if (DEFAULT_MASTER_KEY.equals(encryptionMasterKey)) {
            log.error("✕ 加密 master key 为默认值，拒绝启动。通过环境变量 ENCRYPTION_MASTER_KEY 设置");
            hasIssue = true;
        }

        if (hasIssue) {
            System.exit(1);
        }
    }
}
