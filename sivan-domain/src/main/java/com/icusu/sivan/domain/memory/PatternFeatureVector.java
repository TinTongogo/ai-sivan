package com.icusu.sivan.domain.memory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 模式特征概率分布向量。每个维度是一个概率分布 Map<Enum, Double>，
 * 表示该模板在各维度上的倾向。用于特征驱动的本能模板匹配。
 */
public class PatternFeatureVector {

    private final Map<TaskFeatures.Complexity, Double> complexity;
    private final Map<TaskFeatures.Dependency, Double> dependency;
    private final Map<TaskFeatures.InputStructure, Double> inputStructure;
    private final Map<TaskFeatures.Domain, Double> domain;
    private final Map<TaskFeatures.OutputType, Double> outputType;

    /** 特征权重（各维度对匹配得分的贡献比例）。 */
    public static final double WEIGHT_COMPLEXITY = 0.30;
    public static final double WEIGHT_DEPENDENCY = 0.25;
    public static final double WEIGHT_DOMAIN = 0.20;
    public static final double WEIGHT_INPUT_STRUCTURE = 0.15;
    public static final double WEIGHT_OUTPUT_TYPE = 0.10;

    private static final double WEIGHT_SUM = WEIGHT_COMPLEXITY + WEIGHT_DEPENDENCY
            + WEIGHT_DOMAIN + WEIGHT_INPUT_STRUCTURE + WEIGHT_OUTPUT_TYPE;

    static {
        // 编译期验证权重总和为 1.0
        if (Math.abs(WEIGHT_SUM - 1.0) > 0.001) {
            throw new IllegalStateException("特征权重之和必须为 1.0，当前: " + WEIGHT_SUM);
        }
    }

    private PatternFeatureVector(Builder builder) {
        this.complexity = copyMap(builder.complexity);
        this.dependency = copyMap(builder.dependency);
        this.inputStructure = copyMap(builder.inputStructure);
        this.domain = copyMap(builder.domain);
        this.outputType = copyMap(builder.outputType);
    }

    /** 计算与给定 TaskFeatures 的匹配得分。值域 0~1。 */
    public double matchScore(TaskFeatures query) {
        double score = 0.0;
        score += WEIGHT_COMPLEXITY * probabilityOf(complexity, query.complexity());
        score += WEIGHT_DEPENDENCY * probabilityOf(dependency, query.dependency());
        score += WEIGHT_DOMAIN * probabilityOf(domain, query.domain());
        score += WEIGHT_INPUT_STRUCTURE * probabilityOf(inputStructure, query.inputStructure());
        score += WEIGHT_OUTPUT_TYPE * probabilityOf(outputType, query.outputType());
        return score;
    }

    /** 更新概率分布（加权融合新样本，alpha 为学习率 0~1）。 */
    public PatternFeatureVector merge(TaskFeatures sample, double alpha) {
        return new Builder()
                .complexityMap(mergeMap(this.complexity, sample.complexity(), alpha))
                .dependencyMap(mergeMap(this.dependency, sample.dependency(), alpha))
                .inputStructureMap(mergeMap(this.inputStructure, sample.inputStructure(), alpha))
                .domainMap(mergeMap(this.domain, sample.domain(), alpha))
                .outputTypeMap(mergeMap(this.outputType, sample.outputType(), alpha))
                .build();
    }

    /** 从单一 TaskFeatures 构建概率分布（初始模板用，命中维度概率=1.0，null 维度跳过）。 */
    public static PatternFeatureVector fromTaskFeatures(TaskFeatures features) {
        Builder builder = new Builder();
        if (features.complexity() != null) builder.complexity(features.complexity(), 1.0);
        if (features.dependency() != null) builder.dependency(features.dependency(), 1.0);
        if (features.inputStructure() != null) builder.inputStructure(features.inputStructure(), 1.0);
        if (features.domain() != null) builder.domain(features.domain(), 1.0);
        if (features.outputType() != null) builder.outputType(features.outputType(), 1.0);
        return builder.build();
    }

    // ===== 内部工具 =====

    private static <T extends Enum<T>> double probabilityOf(Map<T, Double> dist, T key) {
        if (dist == null || key == null) return 0.0;
        return dist.getOrDefault(key, 0.0);
    }

    private static <T extends Enum<T>> Map<T, Double> copyMap(Map<T, Double> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<T, Double> copy = new EnumMap<>(source.keySet().iterator().next().getDeclaringClass());
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    private static <T extends Enum<T>> Map<T, Double> mergeMap(Map<T, Double> current, T sampleKey, double alpha) {
        if (sampleKey == null) return copyMap(current);
        Class<T> enumClass = sampleKey.getDeclaringClass();
        Map<T, Double> result = new EnumMap<>(enumClass);
        // 先复制当前分布 × (1-alpha)
        for (T key : enumClass.getEnumConstants()) {
            result.put(key, current.getOrDefault(key, 0.0) * (1 - alpha));
        }
        // 叠加新样本 × alpha
        result.merge(sampleKey, alpha, Double::sum);
        return result;
    }

    // ===== Builder =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<TaskFeatures.Complexity, Double> complexity = new EnumMap<>(TaskFeatures.Complexity.class);
        private final Map<TaskFeatures.Dependency, Double> dependency = new EnumMap<>(TaskFeatures.Dependency.class);
        private final Map<TaskFeatures.InputStructure, Double> inputStructure = new EnumMap<>(TaskFeatures.InputStructure.class);
        private final Map<TaskFeatures.Domain, Double> domain = new EnumMap<>(TaskFeatures.Domain.class);
        private final Map<TaskFeatures.OutputType, Double> outputType = new EnumMap<>(TaskFeatures.OutputType.class);

        public Builder complexity(TaskFeatures.Complexity key, double probability) {
            this.complexity.put(key, probability);
            return this;
        }
        public Builder dependency(TaskFeatures.Dependency key, double probability) {
            this.dependency.put(key, probability);
            return this;
        }
        public Builder inputStructure(TaskFeatures.InputStructure key, double probability) {
            this.inputStructure.put(key, probability);
            return this;
        }
        public Builder domain(TaskFeatures.Domain key, double probability) {
            this.domain.put(key, probability);
            return this;
        }
        public Builder outputType(TaskFeatures.OutputType key, double probability) {
            this.outputType.put(key, probability);
            return this;
        }
        public Builder complexityMap(Map<TaskFeatures.Complexity, Double> map) {
            this.complexity.putAll(map);
            return this;
        }
        public Builder dependencyMap(Map<TaskFeatures.Dependency, Double> map) {
            this.dependency.putAll(map);
            return this;
        }
        public Builder inputStructureMap(Map<TaskFeatures.InputStructure, Double> map) {
            this.inputStructure.putAll(map);
            return this;
        }
        public Builder domainMap(Map<TaskFeatures.Domain, Double> map) {
            this.domain.putAll(map);
            return this;
        }
        public Builder outputTypeMap(Map<TaskFeatures.OutputType, Double> map) {
            this.outputType.putAll(map);
            return this;
        }

        public PatternFeatureVector build() {
            return new PatternFeatureVector(this);
        }
    }

    // ===== Getters =====

    public Map<TaskFeatures.Complexity, Double> getComplexity() { return complexity; }
    public Map<TaskFeatures.Dependency, Double> getDependency() { return dependency; }
    public Map<TaskFeatures.InputStructure, Double> getInputStructure() { return inputStructure; }
    public Map<TaskFeatures.Domain, Double> getDomain() { return domain; }
    public Map<TaskFeatures.OutputType, Double> getOutputType() { return outputType; }
}
