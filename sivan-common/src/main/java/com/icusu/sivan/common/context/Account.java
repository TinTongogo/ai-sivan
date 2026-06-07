package com.icusu.sivan.common.context;

import java.util.UUID;

/**
 * 账户信息记录。
 *
 * @param accountId  当前用户 ID（必填）
 * @param projectId  当前项目 ID（可选，为 null 表示用户全局上下文）
 * @param role       当前用户角色
 */
public record Account(UUID accountId, UUID projectId, String role) {

    public Account(UUID accountId) {
        this(accountId, null, "user");
    }

    public Account(UUID accountId, UUID projectId) {
        this(accountId, projectId, "user");
    }
}
