package com.icusu.sivan.domain.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户 AI 画像实体，替代 Account.preferences JSON blob。
 * <p>
 * 存储用户对 AI 交互的个性化配置：称呼、简介、回复语言、技术栈等。
 * 支持多画像（active 标记当前生效），为自动从对话学习提供持久化载体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    private UUID profileId;
    private UUID accountId;
    private String name;
    private String bio;
    /** 回复语言偏好：zh-CN / en / auto 等 */
    private String aiLanguage;
    /** 技术栈/兴趣标签 */
    @Builder.Default
    private List<String> expertise = new java.util.ArrayList<>();
    /** 是否启用；允许同一账户有多个画像，但仅 active=true 的生效 */
    @Builder.Default
    private boolean active = true;
    /** 画像向量（expertise 各词 embedding 均值），用于语义检索 */
    private float[] vector;
    /** 自动学习开关 */
    @Builder.Default
    private boolean autoLearn = true;
    /** 常见任务类型 */
    @Builder.Default
    private List<String> commonTasks = new java.util.ArrayList<>();
    /** 任务平均复杂度 */
    private Double avgComplexity;
    /** 活跃时段（小时，0-23） */
    private String activeHours;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 合并一个学习信号到当前画像。新信号追加到 expertise（去重），bio 追加新内容。 */
    public void mergeLearningSignal(List<String> newExpertise, String newBio) {
        if (newExpertise != null && !newExpertise.isEmpty()) {
            if (this.expertise == null) {
                this.expertise = newExpertise;
            } else {
                for (String tag : newExpertise) {
                    if (!this.expertise.contains(tag)) {
                        this.expertise.add(tag);
                    }
                }
            }
        }
        if (newBio != null && !newBio.isBlank()) {
            if (this.bio == null || this.bio.isBlank()) {
                this.bio = newBio;
            } else if (!this.bio.contains(newBio)) {
                this.bio += "；" + newBio;
            }
        }
    }
}
