package com.icusu.sivan.infra.shared.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 基础设施层配置：扫描 JPA 实体和 Repository。
 */
@Configuration
@EntityScan(basePackages = "com.icusu.sivan.infra")
@EnableJpaRepositories(basePackages = "com.icusu.sivan.infra")
public class InfrastructureConfig {
}
