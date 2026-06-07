package com.icusu.sivan.web.conversation.service.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.domain.conversation.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 文件附件富化器：告知 LLM 上传了哪些文件，文件内容通过 file_read 工具读取。
 * 不再向 LLM 上下文嵌入文件内容，大文件通过工具按需读取。
 */
@Slf4j
public class FileAttachmentEnricher implements MessageEnricher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ATTACHMENTS_TYPE =
            new TypeReference<List<Map<String, Object>>>() {};

    @Override
    public void enrich(EnrichedMessage enriched, List<Message> allMessages) {
        Message message = enriched.getOriginal();
        if (!message.isUser() || message.getAttachments() == null) return;

        String fileCtx = buildFileAttachmentContext(message.getAttachments());
        if (fileCtx != null) {
            enriched.appendContent("\n\n" + fileCtx);
        }
    }

    private String buildFileAttachmentContext(String attachmentsJson) {
        List<Map<String, Object>> attachments = deserializeAttachments(attachmentsJson);
        if (attachments == null || attachments.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("[用户上传的文件已保存至工作目录，必须使用 file_read 工具读取文件。");
        sb.append("PDF/DOCX/XLSX 等文档 file_read 会自动提取文本内容，即便文件较大也能处理，");
        sb.append("不要用 bash 或 Python 读取文档内容。]\n");
        for (Map<String, Object> att : attachments) {
            String fileName = (String) att.get("fileName");
            String mimeType = (String) att.get("mimeType");
            sb.append("- ").append(fileName).append(" (").append(mimeType).append(")\n");
        }
        return sb.toString();
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
