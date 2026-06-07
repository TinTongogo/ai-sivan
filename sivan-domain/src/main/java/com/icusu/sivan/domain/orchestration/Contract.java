package com.icusu.sivan.domain.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agent 间通信契约聚合根。
 * <p>不变量：executionId/phase/sourceAgent/content 不可为空。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Contract {

    private UUID contractId;
    private UUID executionId;
    private UUID accountId;
    private UUID projectId;
    private Integer phase;
    private String sourceAgent;
    private String targetAgent;
    private String content;
    private String contentType;
    private LocalDateTime createdAt;

    public static final String CONTENT_TYPE_TEXT = "text";
    public static final String CONTENT_TYPE_JSON = "json";
    public static final String CONTENT_TYPE_STRUCTURED = "structured";

    // ===== 领域行为 =====

    /** 校验不变量。 */
    public void validateInvariants() {
        if (executionId == null) throw new IllegalStateException("Contract.executionId 不能为空");
        if (phase == null) throw new IllegalStateException("Contract.phase 不能为空");
        if (sourceAgent == null || sourceAgent.isBlank()) throw new IllegalStateException("Contract.sourceAgent 不能为空");
        if (content == null) throw new IllegalStateException("Contract.content 不能为空");
    }

    /** 根据内容推断类型。 */
    public static String inferContentType(String content) {
        if (content == null || content.isBlank()) return CONTENT_TYPE_TEXT;
        String trimmed = content.strip();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return CONTENT_TYPE_JSON;
        }
        if (content.contains("|") || content.contains("\t")) return CONTENT_TYPE_STRUCTURED;
        return CONTENT_TYPE_TEXT;
    }

    /** 获取契约摘要（前 200 字符）。 */
    public String summary() {
        if (content == null) return "";
        return content.length() <= 200 ? content : content.substring(0, 200) + "...";
    }
}
