package com.icusu.sivan.infra.forest.strategy;

import com.icusu.sivan.common.Mode;
import com.icusu.sivan.domain.forest.port.ProgressStrategy;

/**
 * PARALLEL 进度策略 — 规则与 SEQUENTIAL 一致（全部计数）。
 */
public class ParallelProgressStrategy extends SequentialProgressStrategy {
    @Override
    public Mode supportedMode() { return Mode.PARALLEL; }
}
