package com.icusu.sivan.domain.security;

import java.util.UUID;

/**
 * 安全执行上下文。
 * <p>
 * {@code projectRoot} 由基础设施层根据配置传入，领域层不持有 Spring 依赖。
 */
public record SecurityContext(UUID accountId, UUID projectId, String projectRoot) {
    public SecurityContext(UUID accountId, UUID projectId) {
        this(accountId, projectId, "/data/sivan/" + accountId + "/" + projectId + "/");
    }
}
