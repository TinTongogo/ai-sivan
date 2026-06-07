package com.icusu.sivan.memory.curve;

import com.icusu.sivan.common.enums.MemoryLevel;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 艾宾浩斯遗忘曲线计算引擎。
 * 基于指数衰减模型计算记忆保留率：R = e^(-t * ln(2) / T)
 * 其中 T 为各层级的半衰期，t 为距上次访问的时间。
 */
public class EbbinghausForgettingCurve {

    /** 各层级的半衰期（小时） */
    public static final Map<MemoryLevel, Double> HALF_LIFE_HOURS = Map.of(
            MemoryLevel.SESSION, 1.0,     // 会话级：1 小时
            MemoryLevel.USER,    24.0,    // 用户级：24 小时
            MemoryLevel.TEAM,    168.0,   // 团队级：7 天
            MemoryLevel.PROJECT, 720.0    // 项目级：30 天
    );

    /** 各层级的默认稳定性因子（每次访问补偿） */
    public static final Map<MemoryLevel, Double> REINFORCEMENT_FACTOR = Map.of(
            MemoryLevel.SESSION, 0.5,     // 会话级：补偿 50%
            MemoryLevel.USER,    0.3,     // 用户级：补偿 30%
            MemoryLevel.TEAM,    0.2,     // 团队级：补偿 20%
            MemoryLevel.PROJECT, 0.1      // 项目级：补偿 10%
    );

    /** 建议复习的保留率阈值 */
    public static final double REVIEW_THRESHOLD = 0.6;

    /** 归档阈值（保留率低于此值可归档） */
    public static final double ARCHIVE_THRESHOLD = 0.3;

    /**
     * 计算当前保留率。
     *
     * @param level        记忆层级
     * @param lastAccessed 上次访问时间
     * @param now          当前时间
     * @return 保留率 (0.0 ~ 1.0)
     */
    private static double calculateRetention(MemoryLevel level, LocalDateTime lastAccessed, LocalDateTime now) {
        if (lastAccessed == null || now == null) return 1.0;
        double hoursElapsed = Duration.between(lastAccessed, now).toMinutes() / 60.0;
        if (hoursElapsed <= 0) return 1.0;
        double halfLife = HALF_LIFE_HOURS.getOrDefault(level, 24.0);
        return Math.exp(-hoursElapsed * Math.log(2) / halfLife);
    }

    /**
     * 计算保留率并应用访问次数补偿。
     * 访问次数越多，有效半衰期越长（间隔重复效应）。
     *
     * @param level       记忆层级
     * @param lastAccessed 上次访问时间
     * @param accessCount 访问次数
     * @param now         当前时间
     * @return 补偿后的保留率
     */
    public static double calculateRetentionWithAccess(
            MemoryLevel level, LocalDateTime lastAccessed, int accessCount, LocalDateTime now) {

        double baseRetention = calculateRetention(level, lastAccessed, now);
        if (accessCount <= 1) return baseRetention;

        // 每次访问延长有效半衰期：T_eff = T * (1 + ln(accessCount) * F)
        double factor = REINFORCEMENT_FACTOR.getOrDefault(level, 0.3);
        double halfLife = HALF_LIFE_HOURS.getOrDefault(level, 24.0);
        double effectiveHalfLife = halfLife * (1 + Math.log(accessCount) * factor);
        double hoursElapsed = Duration.between(lastAccessed, now).toMinutes() / 60.0;
        if (hoursElapsed <= 0) return 1.0;

        return Math.exp(-hoursElapsed * Math.log(2) / effectiveHalfLife);
    }

}
