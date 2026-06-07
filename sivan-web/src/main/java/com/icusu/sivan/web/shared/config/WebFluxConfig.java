package com.icusu.sivan.web.shared.config;

import com.icusu.sivan.web.shared.security.CurrentAccountIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

    private final CurrentAccountIdArgumentResolver accountIdResolver;

    public WebFluxConfig(CurrentAccountIdArgumentResolver accountIdResolver) {
        this.accountIdResolver = accountIdResolver;
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(accountIdResolver);
    }
}
