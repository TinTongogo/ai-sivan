package com.icusu.sivan.application.conversation.service.tree;

import com.icusu.sivan.application.conversation.tree.MemoryTree;
import com.icusu.sivan.domain.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryTreeTest {

    @Test
    void empty_returnsEmpty() {
        assertEquals("", new MemoryTree().buildContext("CHAT", 1000));
    }

    @Test
    void importantMemory_retained() {
        MemoryEntry important = MemoryEntry.builder()
                .content("用户偏好使用 Python 进行数据分析")
                .summary("Python 数据分析")
                .important(true)
                .build();

        String result = new MemoryTree()
                .withMemories(List.of(important))
                .buildContext("CHAT", 1000);

        assertNotNull(result);
        assertTrue(result.contains("用户偏好"));
        assertTrue(result.contains("[重要]"));
    }

    @Test
    void nonImportantMemory_truncated() {
        MemoryEntry normal = MemoryEntry.builder()
                .content("a".repeat(500))
                .summary("简短摘要")
                .important(false)
                .build();

        String result = new MemoryTree()
                .withMemories(List.of(normal))
                .buildContext("CHAT", 100);

        assertNotNull(result);
        assertTrue(result.contains("简短摘要")); // summary used
    }

    @Test
    void importantMemory_prioritizedOverNonImportant() {
        MemoryEntry important = MemoryEntry.builder()
                .content("重要决策：使用微服务架构")
                .summary("微服务架构")
                .important(true)
                .build();
        MemoryEntry normal = MemoryEntry.builder()
                .content("普通闲聊内容")
                .summary("闲聊")
                .important(false)
                .build();

        String result = new MemoryTree()
                .withMemories(List.of(normal, important))
                .buildContext("CHAT", 1000);

        assertTrue(result.contains("重要决策"));
    }

    @Test
    void treeType_isMemory() {
        assertEquals("memory", new MemoryTree().treeType());
    }
}
