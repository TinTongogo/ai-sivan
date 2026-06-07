package com.icusu.sivan.domain.shared.vo;

import com.icusu.sivan.common.enums.TokenSource;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Token 计费上下文值对象。
 */
@Data
@Builder
public class TokenContext {

    private UUID accountId;
    private UUID projectId;
    private TokenSource source;
    private UUID agentId;
    private UUID conversationId;
}
