package com.icusu.sivan.web.conversation.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 重新生成请求 DTO。
 */
@Data
public class RegenerateRequest {
    /** 要重新生成的 AI 消息 ID */
    private UUID messageId;
    private UUID modelProviderId;
    private List<UUID> mcpServerIds;

    /** 流式输出（默认开启） */
    private boolean stream = true;
}
