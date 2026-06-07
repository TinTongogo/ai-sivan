package com.icusu.sivan.domain.memory;

import java.util.UUID;

/**
 * 探索状态值对象。
 * 持久化 ε-greedy 探索决策器的调用计数和探索状态，重启后不丢失。
 */
public class ExplorationState {

    private final UUID accountId;
    private int callCount;
    private int lastExplorationCall;

    public ExplorationState(UUID accountId) {
        this.accountId = accountId;
        this.callCount = 0;
        this.lastExplorationCall = -3;
    }

    public ExplorationState(UUID accountId, int callCount, int lastExplorationCall) {
        this.accountId = accountId;
        this.callCount = callCount;
        this.lastExplorationCall = lastExplorationCall;
    }

    public UUID getAccountId() { return accountId; }
    public int getCallCount() { return callCount; }
    public void setCallCount(int callCount) { this.callCount = callCount; }
    public int getLastExplorationCall() { return lastExplorationCall; }
    public void setLastExplorationCall(int lastExplorationCall) { this.lastExplorationCall = lastExplorationCall; }
}
