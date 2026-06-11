package com.icusu.sivan.infra.memory.instinct;

import com.icusu.sivan.domain.forest.service.FeatureExtractor;
import com.icusu.sivan.domain.task.TaskFeatures;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启发式特征提取器 — 通过关键词从任务描述中提取特征向量。
 * <p>
 * 提取的特征不包含原始文本，只包含结构化类型信息。
 * 这是本能模板可复用的关键——"重构登录"和"重构支付"提取出同一组特征。
 */
@Component
public class FeatureExtractorImpl implements FeatureExtractor {

    @Override
    public TaskFeatures extract(String task) {
        if (task == null || task.isBlank()) {
            return new TaskFeatures(TaskFeatures.Complexity.LEVEL_1, null, null, null, null);
        }
        String lower = task.toLowerCase();

        TaskFeatures.Complexity complexity = detectComplexity(lower);
        TaskFeatures.Dependency dependency = detectDependency(lower);
        TaskFeatures.InputStructure input = detectInputStructure(lower);
        TaskFeatures.Domain domain = detectDomain(lower);
        TaskFeatures.OutputType output = detectOutputType(lower);

        return new TaskFeatures(complexity, dependency, input, domain, output);
    }

    private TaskFeatures.Complexity detectComplexity(String task) {
        if (task.contains("简单") || task.contains("小") || task.length() < 20) return TaskFeatures.Complexity.LEVEL_1;
        if (task.contains("复杂") || task.contains("大型") || task.contains("大规模")) return TaskFeatures.Complexity.LEVEL_5;
        if (task.contains("项目") || task.contains("系统")) return TaskFeatures.Complexity.LEVEL_4;
        return TaskFeatures.Complexity.LEVEL_3;
    }

    private TaskFeatures.Dependency detectDependency(String task) {
        if (task.contains("分析") || task.contains("评估")) return TaskFeatures.Dependency.CONDITIONAL;
        if (task.contains("实现") || task.contains("开发")) return TaskFeatures.Dependency.SEQUENTIAL;
        if (task.contains("总结") || task.contains("生成")) return TaskFeatures.Dependency.INDEPENDENT;
        return TaskFeatures.Dependency.SEQUENTIAL;
    }

    private TaskFeatures.InputStructure detectInputStructure(String task) {
        if (task.contains("代码") || task.contains("bug") || task.contains("class ") || task.contains(".java"))
            return TaskFeatures.InputStructure.CODE;
        if (task.contains("?")) return TaskFeatures.InputStructure.Q_A;
        if (task.contains("图片") || task.contains("音频") || task.contains("文件")) return TaskFeatures.InputStructure.MULTI_MODAL;
        return TaskFeatures.InputStructure.FREE_TEXT;
    }

    private TaskFeatures.Domain detectDomain(String task) {
        if (task.contains("代码") || task.contains("重构") || task.contains("实现") || task.contains("开发")
                || task.contains("修复") || task.contains("bug") || task.contains("优化"))
            return TaskFeatures.Domain.CODING;
        if (task.contains("写") || task.contains("文章") || task.contains("文案") || task.contains("生成"))
            return TaskFeatures.Domain.WRITING;
        if (task.contains("分析") || task.contains("总结") || task.contains("比较") || task.contains("对比"))
            return TaskFeatures.Domain.ANALYSIS;
        if (task.contains("设计") || task.contains("创意") || task.contains("头脑风暴"))
            return TaskFeatures.Domain.CREATIVE;
        if (task.contains("调研") || task.contains("搜索") || task.contains("查"))
            return TaskFeatures.Domain.RESEARCH;
        return TaskFeatures.Domain.GENERAL;
    }

    private TaskFeatures.OutputType detectOutputType(String task) {
        if (task.contains("代码") || task.contains("实现") || task.contains("函数"))
            return TaskFeatures.OutputType.CODE;
        if (task.contains("总结") || task.contains("分析") || task.contains("报告"))
            return TaskFeatures.OutputType.LONG_TEXT;
        if (task.contains("json") || task.contains("JSON")) return TaskFeatures.OutputType.JSON;
        if (task.contains("判断") || task.contains("决定") || task.contains("评估"))
            return TaskFeatures.OutputType.DECISION;
        return TaskFeatures.OutputType.SHORT_TEXT;
    }
}
