package com.icusu.sivan.domain.token;

import com.icusu.sivan.common.enums.TokenSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Token 用量记录实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    private UUID tokenUsageId;
    private UUID accountId;
    private UUID projectId;
    private UUID agentId;
    private String modelName;
    private Integer inputTokens;
    private Integer outputTokens;
    private UUID conversationId;
    private TokenSource source;
    private LocalDateTime createdAt;

    /**
     * 从森林 VO 转换创建持久化实体。
     * <p>
     * 确保 thinkingTokens 不丢失：VO 有该字段但 Entity 暂未收录，
     * 已在 outputTokens 中合并（含思考 token）。
     */
    public static TokenUsage fromForestVo(
            com.icusu.sivan.domain.forest.vo.TokenUsage vo,
            UUID accountId, UUID conversationId, String modelName, TokenSource source) {
        return TokenUsage.builder()
                .accountId(accountId)
                .conversationId(conversationId)
                .modelName(modelName)
                .source(source)
                .inputTokens(vo.inputTokens())
                .outputTokens(vo.outputTokens() + vo.thinkingTokens())
                .build();
    }
}
