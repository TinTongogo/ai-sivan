package com.icusu.sivan.application.knowledge.service;

import com.icusu.sivan.application.knowledge.SemanticChunker;
import com.icusu.sivan.domain.shared.vo.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SemanticChunker 单元测试。
 * 验证 Markdown 标题、代码块、段落、句子四种分块策略的正确性。
 */
class SemanticChunkerTest {

    private SemanticChunker chunker;
    private final UUID docId = UUID.randomUUID();
    private static final String KB_NAME = "test-kb";
    private static final String FILENAME = "test.md";

    @BeforeEach
    void setUp() {
        chunker = new SemanticChunker();
    }

    // ===== chunkDocument (集成) =====

    @Test
    void chunkDocument_shouldUseHeaderStrategy() {
        String text = "# 介绍\n这是介绍内容。\n\n## 方法\n这是方法内容。\n\n## 结论\n这是结论内容。";
        List<Chunk> chunks = chunker.chunkDocument(text, KB_NAME, docId, FILENAME);
        assertTrue(chunks.size() >= 3, "至少按三个标题分块");
        for (Chunk c : chunks) {
            assertEquals(KB_NAME, c.getKbName());
            assertEquals(docId, c.getDocId());
        }
    }

    @Test
    void chunkDocument_shouldUseSentenceStrategy_forPlainText() {
        String text = "这是一个较长的纯文本段落。它没有任何标题也没有代码块。"
                + "需要测试语义分块器能否在纯文本场景下正确按句子边界切分。"
                + "虽然这个文本不长，但应该能正常分块。";
        List<Chunk> chunks = chunker.chunkDocument(text, KB_NAME, docId, FILENAME);
        assertFalse(chunks.isEmpty(), "纯文本应能正常分块");
    }

    @Test
    void chunkDocument_shouldHandleEmptyText() {
        List<Chunk> chunks = chunker.chunkDocument("", KB_NAME, docId, FILENAME);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkDocument_shouldHandleNullText() {
        List<Chunk> chunks = chunker.chunkDocument(null, KB_NAME, docId, FILENAME);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunkDocument_shouldPreserveChunkMetadata() {
        String text = "# 标题\n内容。";
        List<Chunk> chunks = chunker.chunkDocument(text, KB_NAME, docId, FILENAME);
        assertFalse(chunks.isEmpty());
        Chunk first = chunks.get(0);
        assertEquals(KB_NAME, first.getKbName());
        assertEquals(docId, first.getDocId());
        assertEquals("text", first.getContentType());
        assertEquals(FILENAME, first.getMetadata().get("filename"));
    }

    @Test
    void chunkDocument_eachChunkUnderMaxChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("# 标题").append(i).append("\n");
            for (int j = 0; j < 10; j++) {
                sb.append("这是第").append(i).append("段第").append(j).append("句。");
            }
            sb.append("\n\n");
        }
        List<Chunk> chunks = chunker.chunkDocument(sb.toString(), KB_NAME, docId, FILENAME);
        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            assertTrue(c.getText().length() <= 500, "分块字符数不应超过500: " + c.getText().length());
        }
    }
}
