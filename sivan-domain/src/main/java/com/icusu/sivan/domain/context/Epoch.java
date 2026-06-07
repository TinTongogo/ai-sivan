package com.icusu.sivan.domain.context;

/**
 * 上下文 Epoch 分段的枚举，定义 Prompt 中各分段的位置和用途。
 * <p>
 * 用于缓存优化：Epoch 0~2 相对稳定，Epoch 3~4 逐轮变化。
 * 遵循「折叠不挪位」原则 — 内容可折叠缩略但分段位置恒定。
 */
public enum Epoch {

    EPOCH_0_SYSTEM(0, "系统提示与基础指令"),
    EPOCH_1_PROFILE(1, "用户长期画像"),
    EPOCH_2_COMPLETED(2, "已完成任务摘要"),
    EPOCH_3_HISTORY(3, "对话历史摘要"),
    EPOCH_4_ACTIVE(4, "活跃上下文与当前问题");

    private final int index;
    private final String label;

    Epoch(int index, String label) {
        this.index = index;
        this.label = label;
    }

    public int getIndex() { return index; }

    public String getLabel() { return label; }

    /** 按索引查找 Epoch。 */
    public static Epoch fromIndex(int index) {
        for (Epoch e : values()) {
            if (e.index == index) return e;
        }
        return EPOCH_4_ACTIVE;
    }

    /** 判断是否适合 LLM Prompt 缓存（0~2 相对稳定）。 */
    public boolean isCacheable() {
        return this == EPOCH_0_SYSTEM || this == EPOCH_1_PROFILE || this == EPOCH_2_COMPLETED;
    }
}
