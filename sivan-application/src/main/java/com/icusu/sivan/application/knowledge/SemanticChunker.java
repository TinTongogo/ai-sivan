package com.icusu.sivan.application.knowledge;

import com.icusu.sivan.domain.shared.vo.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 语义分块器。
 * <p>
 * 按文档的结构化边界（Markdown 标题、代码块、段落）进行分块，
 * 替换简单的按字符数截断的分块策略。不用 LLM，纯正则语义边界检测。
 * <p>
 * 分块优先级：
 * 1. Markdown header（##, #）→ 自然分区边界
 * 2. 代码块（``` 围栏）→ 完整保留，超上限才切
 * 3. 句子边界（句号/感叹号/问号/换行）→ 一般文本兜底
 * 4. 500 字符硬上限确保分块不过大
 */
@Slf4j
@Component
public class SemanticChunker {

    /** 单块最大字符数，与 KnowledgeBaseService.CHUNK_MAX_CHARS 保持一致 */
    private static final int MAX_CHARS = 500;

    /**
     * 对文本进行语义分块。
     *
     * @param text     文档文本内容
     * @param kbName   知识库名称
     * @param docId    文档 ID
     * @param filename 文件名
     * @return 分块列表
     */
    public List<Chunk> chunkDocument(String text, String kbName, UUID docId, String filename) {
        if (text == null || text.isBlank()) return List.of();

        // 阶段 1: 按 Markdown header 边界切分
        List<String> sections = splitByHeaders(text);
        if (sections.size() > 1) {
            return buildChunks(sections, kbName, docId, filename);
        }

        // 阶段 2: 按代码块边界切分
        List<String> codeSegments = splitByCodeBlocks(text);
        if (codeSegments.size() > 1) {
            return buildChunks(codeSegments, kbName, docId, filename);
        }

        // 阶段 3: 按段落切分
        List<String> paragraphs = splitByParagraphs(text);
        if (paragraphs.size() > 1) {
            return buildChunks(paragraphs, kbName, docId, filename);
        }

        // 阶段 4: 兜底 — 按句子边界切分
        List<String> sentences = splitBySentences(text);
        return buildChunks(sentences, kbName, docId, filename);
    }

    /**
     * 按 Markdown 标题边界切分。识别 ## 和 # 开头的行。
     */
    List<String> splitByHeaders(String text) {
        List<String> sections = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder();
        boolean inHeader = false;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.matches("^#{1,6}\\s.*")) {
                if (!current.isEmpty()) {
                    sections.add(current.toString().strip());
                    current = new StringBuilder();
                }
                inHeader = true;
            }
            if (current.length() > 0) current.append("\n");
            current.append(line);
        }
        if (!current.isEmpty()) {
            sections.add(current.toString().strip());
        }

        // 如果全部是同一层级的连续内容（无标题分割），返回空表示不适用
        if (sections.size() <= 1) return List.of();
        return sections;
    }

    /**
     * 按代码块围栏切分。识别 ``` 或 ~~~ 包围的代码块，保持代码块完整。
     */
    List<String> splitByCodeBlocks(String text) {
        List<String> segments = new ArrayList<>();
        String[] parts = text.split("(?=```)|(?<=```)|(?=~~~)|(?<=~~~)", -1);
        StringBuilder current = new StringBuilder();
        boolean inCodeBlock = false;

        for (String part : parts) {
            if (part.startsWith("```") || part.startsWith("~~~")) {
                // 围栏标记切换
                if (inCodeBlock) {
                    // 代码块结束
                    if (current.length() > 0) current.append("\n");
                    current.append(part);
                    segments.add(current.toString().strip());
                    current = new StringBuilder();
                    inCodeBlock = false;
                } else {
                    // 代码块开始 — 先将之前的非代码内容作为段
                    if (!current.isEmpty()) {
                        segments.add(current.toString().strip());
                        current = new StringBuilder();
                    }
                    current.append(part);
                    inCodeBlock = true;
                }
            } else {
                if (current.length() > 0) current.append("\n");
                current.append(part);
            }
        }
        if (!current.isEmpty()) {
            segments.add(current.toString().strip());
        }

        return segments.size() > 1 ? segments : List.of();
    }

    /**
     * 按段落（双换行或单换行后的空行）切分。
     */
    List<String> splitByParagraphs(String text) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> result = new ArrayList<>();
        for (String p : paragraphs) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result.size() > 1 ? result : List.of();
    }

    /**
     * 按句子边界切分（句号/感叹号/问号后跟空格或换行）。
     * 每个句子不超过 MAX_CHARS。
     */
    List<String> splitBySentences(String text) {
        List<String> result = new ArrayList<>();
        // 按句子边界分割
        String[] sentences = text.split("(?<=[。！？.!?])\\s*");
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() > MAX_CHARS) {
                if (!current.isEmpty()) {
                    result.add(current.toString().strip());
                    current = new StringBuilder();
                }
                // 单句超长时按字符切分
                if (trimmed.length() > MAX_CHARS) {
                    for (String seg : splitByMaxChars(trimmed, MAX_CHARS)) {
                        if (!seg.isBlank()) result.add(seg);
                    }
                } else {
                    current.append(trimmed);
                }
            } else {
                if (current.length() > 0) current.append(" ");
                current.append(trimmed);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString().strip());
        }

        return result.isEmpty() ? List.of(text) : result;
    }

    /**
     * 将分段列表转为 Chunk 对象，处理超长段。
     */
    private List<Chunk> buildChunks(List<String> segments, String kbName, UUID docId, String filename) {
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (String segment : segments) {
            if (segment.isBlank()) continue;
            for (String subSegment : splitByMaxChars(segment, MAX_CHARS)) {
                if (subSegment.isBlank()) continue;
                chunks.add(Chunk.builder()
                        .chunkId(UUID.randomUUID().toString())
                        .kbName(kbName)
                        .docId(docId)
                        .text(subSegment)
                        .contentType("text")
                        .contentHash(Chunk.computeHash(subSegment))
                        .metadata(java.util.Map.of("filename", filename, "chunkIndex", index))
                        .build());
                index++;
            }
        }
        return chunks;
    }

    /**
     * 按最大字符数切分，尽量在换行处断开。
     */
    private List<String> splitByMaxChars(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        if (text.length() <= maxChars) {
            result.add(text);
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start) end = newline;
            }
            result.add(text.substring(start, end).trim());
            start = end;
        }
        return result;
    }
}
