package com.icusu.sivan.web.shared.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.TimeZone;

/**
 * Jackson 序列化配置 — 统一日期格式为 ISO 8601（UTC 时区）。
 * 前端收到的日期格式：2026-05-10T06:30:00.000Z
 */
@Configuration
public class JacksonConfig {

    @Bean
    /** 配置 Jackson 全局日期格式为 ISO 8601（UTC），禁用时间戳输出。 */
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .timeZone(TimeZone.getTimeZone("UTC"));
    }
}
