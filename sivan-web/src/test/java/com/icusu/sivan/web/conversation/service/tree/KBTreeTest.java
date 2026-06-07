package com.icusu.sivan.web.conversation.service.tree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KBTreeTest {

    @Test
    void emptyContext_returnsEmpty() {
        assertEquals("", new KBTree().buildContext("CHAT", 1000));
    }

    @Test
    void underBudget_returnsFull() {
        String context = "Spring 框架的事务管理配置说明";
        String result = new KBTree().withContext(context).buildContext("CHAT", 1000);
        assertTrue(result.contains("Spring"));
    }

    @Test
    void overBudget_truncated() {
        String longText = "a".repeat(2000);
        String result = new KBTree().withContext(longText).buildContext("CHAT", 100);
        assertNotNull(result);
        assertTrue(result.length() < 500); // 预算 100t ≈ 200 字符
    }

    @Test
    void treeType_isKb() {
        assertEquals("kb", new KBTree().treeType());
    }
}
