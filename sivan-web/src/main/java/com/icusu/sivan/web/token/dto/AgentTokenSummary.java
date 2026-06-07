package com.icusu.sivan.web.token.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 智能体 Token 消耗汇总。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTokenSummary {
    private UUID agentId;
    private String agentName;
    private long totalInput;
    private long totalOutput;
    private long totalTokens;
}
