package com.icusu.sivan.web;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.server.WebFilter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Web 层集成测试基类，启动 PostgreSQL + pgvector 容器。
 * Docker 不可用时自动跳过。安全配置已被覆盖（permitAll）。
 */
@SpringBootTest(classes = TestWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ExtendWith(AbstractWebIntegrationTest.DockerCheckExtension.class)
@Import({AbstractWebIntegrationTest.TestSecurityConfig.class, AbstractWebIntegrationTest.TestAccountContextConfig.class})
@Sql(scripts = "/seed-test-account.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class AbstractWebIntegrationTest {

    static {
        // 兼容 OrbStack / 非标准 Docker socket 路径：docker-java 内置客户端默认 API
        // 版本 v1.32 在 OrbStack 上会被拒绝（要求 v1.40+），此处提前设好 host 和版本。
        var orbStackSocket = Paths.get(System.getProperty("user.home"), ".orbstack/run/docker.sock");
        if (Files.exists(orbStackSocket)) {
            System.setProperty("docker.host", "unix://" + orbStackSocket.toAbsolutePath());
        }
    }

    protected static final UUID TEST_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @LocalServerPort
    protected int port;

    static PostgreSQLContainer<?> postgres;

/**
     * Docker 可用性检查扩展，不可用时自动跳过测试。
     */
    static class DockerCheckExtension implements BeforeAllCallback {
        /**
         * 检查 Docker 是否可用，不可用则跳过测试。
         */
        @Override
        public void beforeAll(ExtensionContext context) {
            try {
                if (!DockerClientFactory.instance().isDockerAvailable()) {
                    throw new TestAbortedException("Docker 不可用，跳过集成测试");
                }
            } catch (TestAbortedException e) {
                throw e;
            } catch (Exception e) {
                throw new TestAbortedException("Docker 不可用，跳过集成测试: " + e.getMessage());
            }
        }
    }

    /**
     * 启动 PostgreSQL 测试容器（pgvector）。
     */
    static void startPostgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg16")
                    .withDatabaseName("sivan_test")
                    .withUsername("sivan")
                    .withPassword("sivan");
        }
        if (!postgres.isRunning()) {
            postgres.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
    }

    /**
     * 注册 PostgreSQL 容器动态数据源属性。
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        startPostgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.show-sql", () -> "true");
    }

    /**
     * 覆盖安全配置，集成测试中放行所有请求。
     * 使用 @Order(Ordered.HIGHEST_PRECEDENCE) 确保比主 WebSecurityConfig
     * 的 filterChain 优先匹配，从而绕过 JWT 认证。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestSecurityConfig {
        /**
         * 集成测试安全过滤器链，放行所有请求。
         */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public SecurityWebFilterChain testFilterChain(ServerHttpSecurity http) {
            return http
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(auth -> auth.anyExchange().permitAll())
                    .build();
        }
    }

    /**
     * 为测试请求设置默认 accountId 到 exchange attributes，使需要 accountId 的服务正常运行。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestAccountContextConfig {
        @Bean
        @Order(1)
        public WebFilter testAccountContextFilter() {
            return (exchange, chain) -> {
                exchange.getAttributes().put("accountId", TEST_ACCOUNT_ID);
                return chain.filter(exchange);
            };
        }
    }

    /**
     * 拼接本地服务 URL。
     */
    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
