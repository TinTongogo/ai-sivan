package com.icusu.sivan.domain.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextForestTest {

    private static class TestTree implements ContextTree {
        private final String type;
        private final String content;
        TestTree(String type, String content) { this.type = type; this.content = content; }

        @Override public String treeType() { return type; }
        @Override public String buildContext(String scene, int maxTokens) { return content; }
        @Override public int estimateTokens() { return content != null ? content.length() / 2 : 0; }
    }

    @Test
    void emptyForest_returnsEmpty() {
        ContextForest forest = new ContextForest();
        assertTrue(forest.buildAll("CHAT", 1000).isEmpty());
        assertEquals(0, forest.size());
    }

    @Test
    void singleTree_passesThrough() {
        ContextForest forest = new ContextForest()
                .register(new TestTree("conversation", "对话摘要"));
        String result = forest.buildAll("CHAT", 1000);
        assertTrue(result.contains("对话摘要"));
    }

    @Test
    void multipleTrees_combined() {
        ContextForest forest = new ContextForest()
                .register(new TestTree("conversation", "对话摘要"))
                .register(new TestTree("memory", "记忆摘要"));
        String result = forest.buildAll("CHAT", 1000);
        assertTrue(result.contains("对话摘要"));
        assertTrue(result.contains("记忆摘要"));
    }

    @Test
    void squadScene_allocatesBudget() {
        ContextForest forest = new ContextForest()
                .register(new TestTree("conversation", "对话"))
                .register(new TestTree("squad", "Squad"))
                .register(new TestTree("memory", "记忆"));
        // 不抛异常即可
        String result = forest.buildAll("SQUAD_ACTIVE", 1000);
        assertNotNull(result);
    }

    @Test
    void register_overwritesSameType() {
        ContextForest forest = new ContextForest()
                .register(new TestTree("conv", "旧"))
                .register(new TestTree("conv", "新"));
        assertEquals(1, forest.size());
    }

    @Test
    void get_returnsTypedTree() {
        ContextForest forest = new ContextForest()
                .register(new TestTree("test", "value"));
        ContextTree tree = forest.get("test");
        assertNotNull(tree);
        assertEquals("test", tree.treeType());
    }

    @Test
    void all_returnsUnmodifiable() {
        ContextForest forest = new ContextForest()
                .register(new TestTree("t1", "v1"));
        assertThrows(UnsupportedOperationException.class, () -> forest.all().add(new TestTree("t2", "v2")));
    }
}
