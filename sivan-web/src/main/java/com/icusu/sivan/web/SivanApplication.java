package com.icusu.sivan.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.icusu.sivan.web", "com.icusu.sivan.infra", "com.icusu.sivan.agent", "com.icusu.sivan.orch", "com.icusu.sivan.memory"})
/**
 * Sivan（灵枢）应用入口。
 */
public class SivanApplication {

    /** 启动 Spring Boot 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(SivanApplication.class, args);
    }
}
