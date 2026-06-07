package com.icusu.sivan.web.knowledge.service;

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

    // ===== splitByHeaders =====

    @Test
    void splitByHeaders_shouldSplitAtMarkdownHeaders() {
        String text = "# 标题一\n内容一\n\n## 标题二\n内容二\n\n### 标题三\n内容三";
        List<String> sections = chunker.splitByHeaders(text);
        assertEquals(3, sections.size());
        assertTrue(sections.get(0).contains("标题一"));
        assertTrue(sections.get(1).contains("标题二"));
        assertTrue(sections.get(2).contains("标题三"));
    }

    @Test
    void splitByHeaders_shouldReturnEmpty_whenNoHeaders() {
        String text = "纯文本段落\n没有标题\n只有连续内容";
        List<String> sections = chunker.splitByHeaders(text);
        assertTrue(sections.isEmpty());
    }

    @Test
    void splitByHeaders_shouldHandleSingleHeader() {
        String text = "# 只有一个标题\n内容在这里";
        List<String> sections = chunker.splitByHeaders(text);
        assertTrue(sections.isEmpty(), "只有一个 header 时不产生分块");
    }

    // ===== splitByCodeBlocks =====

    @Test
    void splitByCodeBlocks_shouldSeparateCodeBlocks() {
        String text = "开头文本\n```\ncode block\n```\n结尾文本";
        List<String> segments = chunker.splitByCodeBlocks(text);
        assertTrue(segments.size() >= 2);
        boolean hasCodeBlock = segments.stream().anyMatch(s -> s.contains("```"));
        assertTrue(hasCodeBlock);
    }

    @Test
    void splitByCodeBlocks_shouldReturnEmpty_whenNoCodeBlocks() {
        String text = "纯文本，没有代码块";
        List<String> segments = chunker.splitByCodeBlocks(text);
        assertTrue(segments.isEmpty());
    }

    // ===== splitByParagraphs =====

    @Test
    void splitByParagraphs_shouldSplitAtDoubleNewlines() {
        String text = "第一段\n\n第二段\n\n第三段";
        List<String> paragraphs = chunker.splitByParagraphs(text);
        assertEquals(3, paragraphs.size());
    }

    @Test
    void splitByParagraphs_shouldReturnEmpty_whenSingleParagraph() {
        String text = "单独一段内容没有换行分割";
        List<String> paragraphs = chunker.splitByParagraphs(text);
        assertTrue(paragraphs.isEmpty());
    }

    // ===== splitBySentences =====

    @Test
    void splitBySentences_shouldSplitAtChinesePunctuation() {
        String text = "第一句。第二句！第三句？";
        List<String> sentences = chunker.splitBySentences(text);
        assertTrue(sentences.size() >= 1);
    }

    @Test
    void splitBySentences_shouldNotExceedMaxChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句。");
        }
        List<String> sentences = chunker.splitBySentences(sb.toString());
        // 所有分块不应超过 500 字符
        for (String s : sentences) {
            assertTrue(s.length() <= 500, "分块长度不应超过500: " + s.length());
        }
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
