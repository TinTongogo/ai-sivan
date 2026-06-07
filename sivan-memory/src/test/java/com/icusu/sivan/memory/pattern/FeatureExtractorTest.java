package com.icusu.sivan.memory.pattern;

import com.icusu.sivan.domain.task.TaskFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatureExtractorTest {

    FeatureExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new FeatureExtractor();
    }

    @Test
    void shortGreetingShouldBeLevel1() {
        var features = extractor.extractHeuristic("你好");
        assertEquals(TaskFeatures.Complexity.LEVEL_1, features.complexity());
    }

    @Test
    void codeInputShouldDetectCodingDomain() {
        var features = extractor.extractHeuristic("帮我写一个 Java 函数，实现二分查找");
        assertEquals(TaskFeatures.Domain.CODING, features.domain());
    }

    @Test
    void codeInputShouldDetectCodeInputStructure() {
        var features = extractor.extractHeuristic("```\npublic class Test {}\n```");
        assertEquals(TaskFeatures.InputStructure.CODE, features.inputStructure());
    }

    @Test
    void writingTaskShouldDetectWritingDomain() {
        var features = extractor.extractHeuristic("写一篇关于 AI 发展的文章");
        assertEquals(TaskFeatures.Domain.WRITING, features.domain());
    }

    @Test
    void analysisTaskShouldDetectAnalysisDomain() {
        var features = extractor.extractHeuristic("分析一下这个数据集的分布特征");
        assertEquals(TaskFeatures.Domain.ANALYSIS, features.domain());
    }

    @Test
    void questionShouldDetectQaInputStructure() {
        var features = extractor.extractHeuristic("什么是微积分？");
        assertEquals(TaskFeatures.InputStructure.Q_A, features.inputStructure());
    }

    @Test
    void multiStepTaskShouldBeLevel4() {
        var features = extractor.extractHeuristic("首先准备数据，然后训练模型，最后评估结果");
        assertEquals(TaskFeatures.Complexity.LEVEL_4, features.complexity());
    }

    @Test
    void conditionalTaskShouldDetectConditionalDependency() {
        var features = extractor.extractHeuristic("如果用户登录失败，则返回错误提示");
        assertEquals(TaskFeatures.Dependency.CONDITIONAL, features.dependency());
    }

    @Test
    void codeOutputShouldDetectCodeOutputType() {
        var features = extractor.extractHeuristic("写一个 Java class，实现用户注册接口");
        assertEquals(TaskFeatures.OutputType.CODE, features.outputType());
    }

    @Test
    void decisionTaskShouldDetectDecisionOutput() {
        var features = extractor.extractHeuristic("帮我决定选哪个方案最合适");
        assertEquals(TaskFeatures.OutputType.DECISION, features.outputType());
    }

    @Test
    void longTextShouldDetectLongTextOutput() {
        var features = extractor.extractHeuristic("写一篇长文章，介绍机器学习的核心概念，包括监督学习、无监督学习、强化学习等主要分支，以及它们在实际应用中的优缺点。最后讨论未来的发展方向和面临的挑战，以及如何有效应对这些挑战。这就是机器学习的基本内容概述。");
        assertEquals(TaskFeatures.OutputType.LONG_TEXT, features.outputType());
    }

    @Test
    void shortTextShouldDetectShortOutput() {
        var features = extractor.extractHeuristic("嗨");
        assertEquals(TaskFeatures.OutputType.SHORT_TEXT, features.outputType());
    }

    @Test
    void emptyInputShouldReturnLevel1() {
        var features = extractor.extractHeuristic("");
        assertEquals(TaskFeatures.Complexity.LEVEL_1, features.complexity());
        assertNull(features.domain());
    }

    @Test
    void coverageCountsNonNullDimensions() {
        var f1 = extractor.extractHeuristic("你好");
        // LEVEL_1 (len≤10) + SHORT_TEXT (len≤20) = 2
        assertEquals(2, FeatureExtractor.coverage(f1));

        var f2 = extractor.extractHeuristic("");
        int cov = FeatureExtractor.coverage(f2);
        assertTrue(cov < 3);
    }

    @Test
    void l1CoverageEnoughShouldNotCallL2() {
        var features = extractor.extractHeuristic("帮我写一个 Java 函数");
        assertTrue(FeatureExtractor.coverage(features) >= 3);
    }

    @Test
    void l1InsufficientShouldMergeWithL2() {
        // 中性文本，L1 覆盖不足 3 维 → 触发 L2 合并
        var result = extractor.extract("请根据以下信息给出建议",
                desc -> new TaskFeatures(
                        TaskFeatures.Complexity.LEVEL_2,
                        TaskFeatures.Dependency.INDEPENDENT,
                        TaskFeatures.InputStructure.Q_A,
                        TaskFeatures.Domain.GENERAL,
                        TaskFeatures.OutputType.DECISION
                ));
        // L1: complexity=LEVEL_2 (len 11), outputType=SHORT_TEXT (len≤20) → coverage=2
        // L2 fills domain/dependency/inputStructure, L1 wins for complexity and outputType
        assertEquals(TaskFeatures.Complexity.LEVEL_2, result.complexity()); // L1
        assertEquals(TaskFeatures.Dependency.INDEPENDENT, result.dependency()); // L2
        assertEquals(TaskFeatures.Domain.GENERAL, result.domain()); // L2
    }

    @Test
    void mergeShouldPreferL1() {
        var l1 = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_1, null, null,
                TaskFeatures.Domain.CODING, null
        );
        var l2 = new TaskFeatures(
                TaskFeatures.Complexity.LEVEL_3, TaskFeatures.Dependency.SEQUENTIAL,
                null, TaskFeatures.Domain.WRITING, TaskFeatures.OutputType.SHORT_TEXT
        );
        var merged = FeatureExtractor.merge(l1, l2);
        assertEquals(TaskFeatures.Complexity.LEVEL_1, merged.complexity()); // L1
        assertEquals(TaskFeatures.Dependency.SEQUENTIAL, merged.dependency()); // L2
        assertEquals(TaskFeatures.Domain.CODING, merged.domain()); // L1
        assertEquals(TaskFeatures.OutputType.SHORT_TEXT, merged.outputType()); // L2
    }
}
