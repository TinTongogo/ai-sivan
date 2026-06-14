# ADR-002：Spring Boot 版本选型 — Spring Boot 4.0.x

> **状态**：✅ 已决策
> **日期**：2026-06-05
> **作者**：系统架构组

---

## 背景

实现计划原始指定 **Spring Boot 3.4.5**。但由于两个外部变化需要重新评估：

1. **JDK 25 LTS 选型**（见 [ADR-001](40-决策/ADR-001-JDK25-选型.md)）— SB 3.4.x 最高仅支持 JDK 24
2. **SB 3.4.5 OSS 支持已于 2025 年 12 月终止** — 不再收到安全补丁和 bugfix

## 可选方案

### 方案 A：Spring Boot 3.5.x（桥梁版本）

| 属性 | 值 |
|------|-----|
| 最新版本 | 3.5.9 |
| JDK 支持 | 17-25 |
| 基线 | Spring Framework 6.2.x, Jakarta EE 10 |
| OSS 支持截止 | 2026-06 |
| 商业支持截止 | 2032-06 |

### 方案 B：Spring Boot 4.0.x（最新）

| 属性 | 值 |
|------|-----|
| 最新版本 | 4.0.4 |
| JDK 支持 | 17-25 **推荐 JDK 25** |
| 基线 | **Spring Framework 7.0**, **Jakarta EE 11** |
| OSS 支持截止 | 2026-12 |
| 商业支持截止 | 2027-12 |

---

## Spring Boot 4.0.x 核心变化分析

### 1. 自动配置模块化 — 架构级影响

`spring-boot-autoconfigure` 从单一 6.2MB JAR 拆分为 **47 个技术专属模块**。

```
旧：spring-boot-starter-web         ← 一个大包管全部
新：spring-boot-starter-webmvc      ← 只含 MVC 自动配置
    spring-boot-starter-actuator    ← Actuator 自动配置
    spring-boot-starter-flyway      ← Flyway 显式声明
    ...
```

> **对 Sivan 的影响**：正向利好。我们的多模块结构天然适配显式 starter 声明。`pom.xml` 的依赖更精确可追溯，符合"精确胜过隐式"的设计哲学。但需要为每个使用的技术**显式声明 starter**（如 Flyway、R2DBC）。

### 2. Jackson 3 迁移

| Jackson 2 | Jackson 3 |
|-----------|-----------|
| `com.fasterxml.jackson.core:jackson-databind` | `tools.jackson.core:jackson-databind` |
| `com.fasterxml.jackson.core:jackson-annotations` | **保持** `com.fasterxml.jackson.core`（兼容） |
| `ObjectMapper mapper = new ObjectMapper()` | `JsonMapper mapper = JsonMapper.builder().build()` |
| 日期序列化为时间戳 | 日期序列化为 ISO-8601 字符串 |
| 属性无序 | 属性按字母序 |
| 异常为 checked | 异常为 **unchecked** `JacksonException` |

> **对 Sivan 的影响**：**零迁移成本**。新项目没有 Jackson 2 代码需要改写。Spring Boot 4 提供 `spring.jackson.use-jackson2-defaults=true` 作为过渡，但 Sivan 不需要。Jackson 3 的 `@JsonProperty` 等注解仍保留在 `com.fasterxml.jackson` 兼容包名下。

### 3. Jakarta EE 11 基线

Servlet 6.1 / JPA 3.2 / Bean Validation 3.1。

> **对 Sivan 的影响**：零。新项目无 `javax.*` 旧代码。

### 4. JSpecify 空安全

Spring 切换到 `org.jspecify.annotations`，替代 `org.springframework.lang`。`@NullMarked` 包级注解可在编译期保证空安全。

> **对 Sivan 的影响**：正向。与 Sivan 强调接口契约清晰的设计哲学一致。`@NullMarked` 加在领域包上即可让编译器背书返回值是否可为 null。

### 5. 内置弹性能力

`@Retryable` / `@ConcurrencyLimit` 直接集成到 Spring Framework 7.0。

> **对 Sivan 的影响**：Phase 2 模型路由的 `RetryDecorator` 不再需要第三方库。直接使用框架原生的 `@Retryable`。

### 6. 虚拟线程深度集成

Hibernate 7 原生支持虚拟线程，Actuator 新增 `/virtual-threads` 端点。

> **对 Sivan 的影响**：Sivan 的 Reactor 非阻塞模型不变，但 Repository 层的 blocking 操作（Flyway 迁移、R2DBC 的部分场景）可优雅包装为虚拟线程。

### 7. 其他值得注意的变化

| 变化 | 对 Sivan 的影响 |
|------|----------------|
| **Undertow 被移除** | ✅ 无影响 — 用 Netty + WebFlux |
| **`spring-boot-starter-web` → `webmvc`** | ✅ 无影响 — 用 WebFlux |
| **R2DBC 驱动迁移：`io.r2dbc` → `org.postgresql`** | ⚠️ 已在 infrastructure POM 中适配 |
| **OpenTelemetry Starter** | ✅ Phase 4 可观测性直接使用 |
| **GraalVM Native Image 成熟** | ⏸ Phase 4 部署评估 |
| **声明式 HTTP 客户端 `@HttpServiceClient`** | ⏸ Phase 2 多模型适配器可选使用 |

---

## 决策：选择 Spring Boot 4.0.4

### 理由

1. **JDK 25 原生支持** — SB 4.0.x 将 JDK 25 作为一等公民，与 ADR-001 一致
2. **零迁移成本** — 新项目没有需要升级的依赖代码
3. **模块化架构对齐** — 显式 starter 声明与 Sivan 的多模块设计一致
4. **保留 3.x 互操作性** — 通过 `spring.jackson.use-jackson2-defaults=true` 可临时保留 Jackson 2 行为，但 Sivan 不需要
5. **支持周期合理** — OSS 至 2026-12，商业支持至 2027-12，足够完成开发

### 否决方案 A（SB 3.5.x）的原因

- SB 3.5.x 仍基于 Jakarta EE 10 + Spring Framework 6.x，意味着未来仍需一次大版本迁移
- 商业支持虽长（2032），但 Sivan 的交付周期在 2026-2027，没必要追求 2032

### 未解决的问题

- Spring Boot 4.0.x 版本较新（2025-11 发布），部分第三方库的集成可能有边缘情况
- 已通过 dependency management 在父 POM 中引入 `tools.jackson:jackson-bom` 确保版本一致性

### 依赖配置

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.4</version>
</parent>

<!-- 注意：starter 需要显式声明 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- R2DBC 驱动已迁移到 org.postgresql -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>
```

---

## 参考文献

- [Spring Boot 4.0 Release Highlights](https://spring.io/projects/release-highlights)
- [Introducing Jackson 3 support in Spring](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring)
- [Spring Boot 4.0.0-RC1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0.0-RC1-Release-Notes)
- [Modularizing Spring Boot](https://spring.io/blog/2025/10/28/modularizing-spring-boot)
- [Spring Security 7.0 Migration Guide](https://docs.spring.io/spring-security/reference/migration/index.html)
