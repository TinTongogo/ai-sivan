package com.icusu.sivan.application.forest;

import com.icusu.sivan.infra.forest.entity.IntentPrototypeEntity;
import com.icusu.sivan.infra.forest.repository.IntentPrototypeJpaRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 意图原型应用服务 — 封装意图原型的持久化操作，
 * 避免 web 层直接操作 JPA Entity/Repository。
 */
@Service
public class IntentPrototypeService {

    private final IntentPrototypeJpaRepository prototypeRepo;

    public IntentPrototypeService(IntentPrototypeJpaRepository prototypeRepo) {
        this.prototypeRepo = prototypeRepo;
    }

    /**
     * 更新闲聊和任务意图原型文本，立即持久化。
     */
    public void updatePrototypes(String chatPrototypeText, String taskPrototypeText) {
        var now = OffsetDateTime.now();
        prototypeRepo.save(IntentPrototypeEntity.builder()
                .prototypeKey("chat").prototypeText(chatPrototypeText).updatedAt(now).build());
        prototypeRepo.save(IntentPrototypeEntity.builder()
                .prototypeKey("task").prototypeText(taskPrototypeText).updatedAt(now).build());
    }
}
