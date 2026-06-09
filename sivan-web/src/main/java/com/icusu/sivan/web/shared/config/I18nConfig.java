package com.icusu.sivan.web.shared.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.i18n.LocaleContextResolver;

import java.util.Locale;

/**
 * 国际化配置 — 基于 Accept-Language 请求头解析客户端语言偏好。
 * <p>
 * 默认语言：中文（zh-CN）。未支持的语种回退到默认语言。
 * 消息文件位于 {@code classpath:messages.properties}（中文）
 * 和 {@code classpath:messages_en.properties}（英文）。
 */
@Configuration
public class I18nConfig {

    /** MessageSource 由 Spring Boot 自动配置（classpath:messages.properties 存在时），
     *  此处显式声明 Bean 以便自定义配置。 */
    @Bean
    public MessageSource messageSource() {
        var source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /** 基于 Accept-Language 请求头的 Locale 解析器。 */
    @Bean
    public LocaleContextResolver localeContextResolver() {
        var resolver = new AcceptHeaderLocaleContextResolver();
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return resolver;
    }
}
