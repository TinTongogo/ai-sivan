package com.icusu.sivan.domain.orchestration;

/**
 * 阶段产出的产物引用。对应 execution_artifacts 表的一条记录。
 *
 * @param artifactId   execution_artifacts 表主键
 * @param artifactType 产物类型
 * @param name         产物名称，如「用户登录 API 设计」
 * @param description  一句话描述
 */
public record ArtifactRef(
        String artifactId,
        ArtifactType artifactType,
        String name,
        String description
) {
    public ArtifactRef {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId 不能为空");
        }
        if (artifactType == null) {
            throw new IllegalArgumentException("artifactType 不能为空");
        }
    }
}
