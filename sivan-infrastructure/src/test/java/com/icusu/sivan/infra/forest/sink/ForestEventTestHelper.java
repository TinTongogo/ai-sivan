package com.icusu.sivan.infra.forest.sink;

import com.icusu.sivan.domain.forest.ForestEvent;

/**
 * 测试辅助工具 — 创建 ForestEvent 实例。
 */
public final class ForestEventTestHelper {

    private ForestEventTestHelper() { }

    public static ForestEvent lifecycleEvent() {
        return ForestEvent.lifecycle("n1", "f1", "a1", ForestEvent.EventType.LIFECYCLE);
    }

    public static ForestEvent errorEvent(String message) {
        return ForestEvent.error("n1", "f1", "a1", message);
    }

    public static ForestEvent pauseEvent(String reason) {
        return ForestEvent.pause("n1", "f1", "a1", reason);
    }
}
