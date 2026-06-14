package com.icusu.sivan.application.conversation.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.infra.file.DocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档附件富化器：将 PDF/DOCX 等文档通过 Tika 提取文本后注入消息内容。
 * <p>
 * 小文档（≤100KB）直接提取文本嵌入 LLM 上下文，大文档留待 LLM 通过 file_read 工具读取。
 */
@Slf4j
public class DocumentAttachmentEnricher implements MessageEnricher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ATTACHMENTS_TYPE =
            new TypeReference<List<Map<String, Object>>>() {};

    /** 超过此大小的文档不嵌入上下文，由 file_read 兜底。 */
    private static final long MAX_EMBED_SIZE = 100 * 1024;

    /** 嵌入文本最大字符数。 */
    private static final int MAX_EXTRACT_CHARS = 50_000;

    private final DocumentTextExtractor textExtractor;
    private final FileStoragePort fileStoragePort;

    public DocumentAttachmentEnricher(DocumentTextExtractor textExtractor, FileStoragePort fileStoragePort) {
        this.textExtractor = textExtractor;
        this.fileStoragePort = fileStoragePort;
    }

    @Override
    public void enrich(EnrichedMessage enriched, List<Message> allMessages) {
        Message message = enriched.getOriginal();
        if (!message.isUser() || message.getAttachments() == null) return;

        List<Map<String, Object>> attachments = deserializeAttachments(message.getAttachments());
        if (attachments == null || attachments.isEmpty()) return;

        UUID accountId = message.getAccountId();

        for (Map<String, Object> att : attachments) {
            String mimeType = (String) att.get("mimeType");
            String fileIdStr = att.get("fileId") != null ? att.get("fileId").toString() : null;
            String fileName = (String) att.get("fileName");
            if (fileIdStr == null || !isDocumentMimeType(mimeType)) continue;

            try {
                UUID fileId = UUID.fromString(fileIdStr);
                byte[] bytes = fileStoragePort.loadBytes(accountId, fileId);
                if (bytes == null || bytes.length == 0) {
                    log.warn("文档附件字节为空: fileId={}", fileId);
                    continue;
                }
                if (bytes.length > MAX_EMBED_SIZE) {
                    log.info("文档超过 {}KB 嵌入阈值，跳过富化，由 file_read 兜底: fileId={}, size={}KB",
                            MAX_EMBED_SIZE / 1024, fileId, bytes.length / 1024);
                    continue;
                }

                String extractedText = textExtractor.extractText(bytes, mimeType, MAX_EXTRACT_CHARS);
                if (extractedText != null && !extractedText.isBlank()) {
                    enriched.appendContent("\n\n[文档附件: " + (fileName != null ? fileName : fileId) + "]\n" + extractedText);
                    log.info("文档附件已富化嵌入: fileId={}, extractedChars={}", fileId, extractedText.length());
                } else {
                    log.warn("文档附件提取文本为空: fileId={}, mimeType={}", fileId, mimeType);
                }
            } catch (Exception e) {
                log.warn("文档附件富化失败: fileId={}, {}", fileIdStr, e.getMessage());
            }
        }
    }

    /** 判断是否为文档类型（PDF 等二进制文档，可走 Tika 文本提取）。 */
    public static boolean isDocumentMimeType(String mimeType) {
        if (mimeType == null) return false;
        String mt = mimeType.toLowerCase();
        return mt.equals("application/pdf")
                || mt.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || mt.equals("application/msword")
                || mt.equals("application/vnd.ms-powerpoint")
                || mt.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                || mt.equals("application/vnd.ms-excel")
                || mt.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private static List<Map<String, Object>> deserializeAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) return null;
        try {
            return MAPPER.readValue(attachmentsJson, ATTACHMENTS_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("反序列化附件列表失败", e);
            return null;
        }
    }
}
