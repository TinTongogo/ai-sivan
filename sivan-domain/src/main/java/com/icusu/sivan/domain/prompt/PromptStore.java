package com.icusu.sivan.domain.prompt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提示词包存储接口 — 领域层抽象，不依赖任何框架。
 */
public interface PromptStore {
    void save(PromptPack pack);
    Optional<PromptPack> findBySceneAndPersona(String scene, String personaId);
    List<PromptPack> findByPersona(String personaId);
    void delete(UUID packId);
}
