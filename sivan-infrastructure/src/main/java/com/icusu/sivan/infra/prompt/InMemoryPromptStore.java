package com.icusu.sivan.infra.prompt;

import com.icusu.sivan.domain.prompt.PromptPack;
import com.icusu.sivan.domain.prompt.PromptStore;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存提示词存储 — Phase 0 实现，重启后丢失。
 */
@Component
public class InMemoryPromptStore implements PromptStore {

    private final Map<String, PromptPack> store = new ConcurrentHashMap<>();

    private String key(String scene, String personaId) {
        return scene + "." + personaId;
    }

    @Override
    public void save(PromptPack pack) {
        store.put(key(pack.scene(), pack.personaId()), pack);
    }

    @Override
    public Optional<PromptPack> findBySceneAndPersona(String scene, String personaId) {
        return Optional.ofNullable(store.get(key(scene, personaId)));
    }

    @Override
    public List<PromptPack> findByPersona(String personaId) {
        return store.values().stream()
                .filter(p -> p.personaId().equals(personaId))
                .toList();
    }

    @Override
    public void delete(UUID packId) {
        store.values().removeIf(p -> p.packId().equals(packId));
    }

    /** 热加载：删除缓存条目，下次请求从 DB 重新加载。 */
    public void reload(String scene, String personaId) {
        store.remove(key(scene, personaId));
    }
}
