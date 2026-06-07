package com.icusu.sivan.domain.orchestration;

import java.util.HashMap;
import java.util.Map;

/**
 * 阶段间上下文包裹。每个阶段的输入不再是一个字符串，而是一个包含完整上下文的包裹对象。
 * 可从纯字符串构造以保持向下兼容。
 */
public class ContextPackage {

    private final String input;
    private final Map<Integer, PhaseOutput> phaseOutputs;
    private final String taskDescription;
    private final String hitlOverride;
    private final String progressSummary;

    public ContextPackage(String input) {
        this.input = input;
        this.phaseOutputs = new HashMap<>();
        this.taskDescription = input;
        this.hitlOverride = null;
        this.progressSummary = null;
    }

    public ContextPackage(String input, String taskDescription,
                          Map<Integer, PhaseOutput> phaseOutputs,
                          String hitlOverride, String progressSummary) {
        this.input = input;
        this.taskDescription = taskDescription;
        this.phaseOutputs = phaseOutputs != null ? new HashMap<>(phaseOutputs) : new HashMap<>();
        this.hitlOverride = hitlOverride;
        this.progressSummary = progressSummary;
    }

    /** 从 PhaseResult 构建 ContextPackage（纯文本兼容模式）。 */
    public static ContextPackage fromPhaseResult(String resultContent, String taskDescription,
                                                  Map<Integer, PhaseOutput> phaseOutputs) {
        return new ContextPackage(resultContent, taskDescription, phaseOutputs, null, null);
    }

    /** 追加一个阶段的输出到上下文。 */
    public ContextPackage withPhaseOutput(int phaseIndex, PhaseOutput output) {
        Map<Integer, PhaseOutput> updated = new HashMap<>(this.phaseOutputs);
        updated.put(phaseIndex, output);
        return new ContextPackage(output != null ? output.content() : this.input,
                this.taskDescription, updated, this.hitlOverride, this.progressSummary);
    }

    // ===== Getters =====

    public String getInput() { return input; }
    public Map<Integer, PhaseOutput> getPhaseOutputs() { return phaseOutputs; }
    public String getTaskDescription() { return taskDescription; }
    public String getHitlOverride() { return hitlOverride; }
    public String getProgressSummary() { return progressSummary; }
}
