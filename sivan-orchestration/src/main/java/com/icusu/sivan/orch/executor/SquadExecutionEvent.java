package com.icusu.sivan.orch.executor;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * Squad 执行进度事件，用于 SSE 推送。
 */
@Data
@AllArgsConstructor
public class SquadExecutionEvent {

    private UUID executionId;
    private String status;
    private Integer currentPhase;
    private String phaseName;
    private String message;
}
