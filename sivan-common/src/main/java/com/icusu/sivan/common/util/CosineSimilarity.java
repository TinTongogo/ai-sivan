package com.icusu.sivan.common.util;

/**
 * 余弦相似度计算工具。
 */
public final class CosineSimilarity {

    private CosineSimilarity() {}

    /**
     * 计算两个 float 向量的余弦相似度。
     *
     * @return 余弦相似度（0~1），异常情况返回 0
     */
    public static double compute(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dotProduct / denom;
    }
}
