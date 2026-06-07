package com.icusu.sivan.infra;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 集成测试基类，启动 PostgreSQL + pgvector 容器。
 * Docker 不可用时自动跳过。
 * <p>
 * 使用自定义 DockerCheckExtension 确保在 Spring 上下文初始化之前检查 Docker。
 */
@SpringBootTest(classes = TestInfrastructureApplication.class)
@ExtendWith(AbstractIntegrationTest.DockerCheckExtension.class)
@ExtendWith(SpringExtension.class)
public abstract class AbstractIntegrationTest {

    static {
        // 兼容 OrbStack：docker-java 默认找 /var/run/docker.sock，OrbStack 放在 ~/.orbstack/run/
        var orbStackSocket = Paths.get(System.getProperty("user.home"), ".orbstack/run/docker.sock");
        if (Files.exists(orbStackSocket)) {
            System.setProperty("docker.host", "unix://" + orbStackSocket.toAbsolutePath());
        }
    }

    static PostgreSQLContainer<?> postgres;

    /**
     * Docker 可用性检查扩展，在 SpringExtension 之前执行。
     */
    static class DockerCheckExtension implements BeforeAllCallback {
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
     * 初始化并启动 PostgreSQL 容器。
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
        registry.add("sivan.file.root-path", () -> System.getProperty("java.io.tmpdir") + "/sivan-test");
        registry.add("sivan.encryption.master-key", () -> "dev-master-key-change-in-production");
    }
}
