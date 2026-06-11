package com.icusu.sivan.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 集成测试专用 Spring Boot 入口，扫描 web / infra / domain 三层。
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.icusu.sivan.web", "com.icusu.sivan.infra", "com.icusu.sivan.domain", "com.icusu.sivan.agent", "com.icusu.sivan.core"})
public class TestWebApplication {
}
