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
}
