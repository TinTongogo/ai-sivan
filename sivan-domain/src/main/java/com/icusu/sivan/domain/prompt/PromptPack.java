package com.icusu.sivan.domain.prompt;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 提示词包 — 一个场景 + 一个人格下的完整提示词集合。
 * 版本化、可回滚。
 */
public class PromptPack {
    private final UUID packId;
    private final String scene;       // CHAT, REVIEW, CODING, ANALYSIS
    private final String personaId;
    private final int version;
    private final String systemTemplate;  // 含 {{variable}} 占位符
    private final LocalDateTime createdAt;

    public PromptPack(String scene, String personaId, int version, String systemTemplate) {
        this.packId = UUID.randomUUID();
        this.scene = scene;
        this.personaId = personaId;
        this.version = version;
        this.systemTemplate = systemTemplate;
        this.createdAt = LocalDateTime.now();
    }

    public UUID packId() { return packId; }
    public String scene() { return scene; }
    public String personaId() { return personaId; }
    public int version() { return version; }
    public String systemTemplate() { return systemTemplate; }
    public LocalDateTime createdAt() { return createdAt; }
}
