package com.icusu.sivan.domain.memory;

/**
 * 任务固有特征五维值对象。
 * 用于特征驱动的本能模板匹配和执行路径判定。
 * <p>
 * {@link #toString()} 输出结构化特征摘要，用于路由系统的 feature_hash 计算，
 * 使语义相似的任务映射到相同哈希，提升路由泛化能力。
 *
 * @param complexity     复杂度等级（LEVEL_1~5）
 * @param dependency     依赖关系类型
 * @param inputStructure 输入结构模式
 * @param domain         领域分类
 * @param outputType     输出类型
 */
public record TaskFeatures(
        Complexity complexity,
        Dependency dependency,
        InputStructure inputStructure,
        Domain domain,
        OutputType outputType
) {

    public enum Complexity {
        LEVEL_1, // 打招呼、简单问答
        LEVEL_2, // 单一问题回复、信息查询
        LEVEL_3, // 需要多步分析的写作/编码
        LEVEL_4, // 跨领域多步骤任务
        LEVEL_5  // 大型项目级任务
    }

    public enum Dependency {
        INDEPENDENT,   // 各步骤无依赖关系
        SEQUENTIAL,    // 步骤间有前后依赖
        PARALLEL,      // 可并发执行的子任务
        CONDITIONAL    // 存在条件分支
    }

    public enum InputStructure {
        FREE_TEXT,        // 自由文本
        Q_A,              // 问答式
        CODE,             // 代码/报错信息
        MULTI_MODAL,      // 多模态输入
        STRUCTURED_DATA   // 结构化数据
    }

    public enum Domain {
        CODING,    // 代码、Debug、架构相关
        WRITING,   // 文章、文案、创意写作
        ANALYSIS,  // 数据分析、比较、总结
        CREATIVE,  // 头脑风暴、创意设计
        RESEARCH,  // 调研、资料整理
        GENERAL    // 无法归类的通用请求
    }

    public enum OutputType {
        SHORT_TEXT,      // 短文本回复
        LONG_TEXT,       // 长文本/文章
        CODE,            // 代码产出
        JSON,            // 结构化JSON
        MULTI_MODAL,     // 多模态输出
        DECISION         // 决策/判断结果
    }

    /** 从输入文本中提取结构化特征。 */
    public static TaskFeatures fromContent(String input) {
        return new TaskFeatures(
                detectComplexity(input),
                detectDependency(input),
                detectInputStructure(input),
                detectDomain(input),
                detectOutputType(input)
        );
    }

    // ===== 启发式检测方法 =====

    private static Complexity detectComplexity(String input) {
        int len = input != null ? input.length() : 0;
        if (len < 30) return Complexity.LEVEL_1;
        if (len < 200) return Complexity.LEVEL_2;
        if (len < 800) return Complexity.LEVEL_3;
        if (len < 3000) return Complexity.LEVEL_4;
        return Complexity.LEVEL_5;
    }

    private static Dependency detectDependency(String input) {
        if (input == null) return Dependency.INDEPENDENT;
        String lower = input.toLowerCase();
        if (lower.contains("同时") || lower.contains("并行") || lower.contains("parallel"))
            return Dependency.PARALLEL;
        if (lower.contains("如果") || lower.contains("判断") || lower.contains("条件") || lower.contains("否则"))
            return Dependency.CONDITIONAL;
        if (lower.contains("然后") || lower.contains("之后再") || lower.contains("接着") || lower.contains("步骤"))
            return Dependency.SEQUENTIAL;
        return Dependency.INDEPENDENT;
    }

    private static InputStructure detectInputStructure(String input) {
        if (input == null) return InputStructure.FREE_TEXT;
        String lower = input.toLowerCase();
        if (input.contains("```") || lower.contains("function") || lower.contains("class ") || lower.contains("error:"))
            return InputStructure.CODE;
        if (input.contains("\n") && input.contains("?")) return InputStructure.Q_A;
        if (input.contains("\"") && input.contains("{") && input.contains("}")) return InputStructure.STRUCTURED_DATA;
        return InputStructure.FREE_TEXT;
    }

    private static Domain detectDomain(String input) {
        if (input == null) return Domain.GENERAL;
        String lower = input.toLowerCase();
        if (lower.contains("代码") || lower.contains("写") || lower.contains("编程") || lower.contains("debug") || lower.contains("api") || lower.contains("sql"))
            return Domain.CODING;
        if (lower.contains("文章") || lower.contains("写") || lower.contains("文案") || lower.contains("翻译"))
            return Domain.WRITING;
        if (lower.contains("分析") || lower.contains("对比") || lower.contains("总结") || lower.contains("数据"))
            return Domain.ANALYSIS;
        if (lower.contains("创意") || lower.contains("设计") || lower.contains("头脑风暴"))
            return Domain.CREATIVE;
        if (lower.contains("调研") || lower.contains("研究") || lower.contains("搜索"))
            return Domain.RESEARCH;
        return Domain.GENERAL;
    }

    private static OutputType detectOutputType(String input) {
        if (input == null) return OutputType.SHORT_TEXT;
        String lower = input.toLowerCase();
        if (lower.contains("代码") || lower.contains("脚本") || lower.contains("编程"))
            return OutputType.CODE;
        if (lower.contains("json") || lower.contains("结构化"))
            return OutputType.JSON;
        if (lower.contains("文章") || lower.contains("报告") || lower.contains("长文"))
            return OutputType.LONG_TEXT;
        if (lower.contains("图片") || lower.contains("图像") || lower.contains("图表"))
            return OutputType.MULTI_MODAL;
        if (lower.contains("决定") || lower.contains("选择") || lower.contains("推荐"))
            return OutputType.DECISION;
        return OutputType.SHORT_TEXT;
    }
}
