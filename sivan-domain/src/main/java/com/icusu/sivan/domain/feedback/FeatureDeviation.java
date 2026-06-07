package com.icusu.sivan.domain.feedback;

import java.util.List;

/**
 * 特征偏差分析。记录模板匹配的偏差信息，用于驱动模板优化。
 *
 * @param featureMismatch         特征提取是否偏差
 * @param matchScore              实际匹配得分
 * @param expectedScore           理想匹配得分（事后计算）
 * @param mismatchDimensions      偏差维度列表
 * @param suggestedFeatureAdjust  LLM 建议的特征修正
 */
public record FeatureDeviation(
        boolean featureMismatch,
        double matchScore,
        double expectedScore,
        List<String> mismatchDimensions,
        String suggestedFeatureAdjust
) {

    public FeatureDeviation {
        if (mismatchDimensions == null) {
            mismatchDimensions = List.of();
        }
    }
}
