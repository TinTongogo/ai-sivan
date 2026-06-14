package com.icusu.sivan.infra;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 基础设施模块的测试入口，仅扫描 infra 和 domain 包。
 * 排除 InfrastructureConfig 以避免重复注册 JPA Repository。
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.icusu.sivan.infra", "com.icusu.sivan.domain"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.icusu\\.sivan\\.infra\\.shared\\.config\\.InfrastructureConfig"))
@EntityScan(basePackages = {"com.icusu.sivan.infra"})
@EnableJpaRepositories(basePackages = {"com.icusu.sivan.infra"})
public class TestInfrastructureApplication {
}
