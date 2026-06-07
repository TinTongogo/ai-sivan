package com.icusu.sivan.web.conversation.service.tree;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolChainTreeTest {

    @Test
    void empty_returnsEmpty() {
        assertEquals("", new ToolChainTree().buildContext("CHAT", 1000));
    }

    @Test
    void underBudget_showsAll() {
        List<String> calls = List.of(
                "get_weather(北京) → 晴, 25°C",
                "send_email(to=user@example.com, subject=报告)"
        );

        String result = new ToolChainTree()
                .withToolCalls(calls)
                .buildContext("CHAT", 1000);

        assertNotNull(result);
        assertTrue(result.contains("get_weather"));
        assertTrue(result.contains("send_email"));
    }

    @Test
    void overBudget_latestExpanded() {
        List<String> calls = List.of(
                "old_call_1 → result1",
                "old_call_2 → result2",
                "old_call_3 → result3",
                "recent_call → recent_result"
        );

        String result = new ToolChainTree()
                .withToolCalls(calls)
                .buildContext("CHAT", 10); // very tight budget

        assertNotNull(result);
        // 最新的调用应优先保留
        assertTrue(result.contains("recent_call") || result.contains("其他"));
    }

    @Test
    void treeType_isToolchain() {
        assertEquals("toolchain", new ToolChainTree().treeType());
    }
}
