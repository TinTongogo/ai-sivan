package com.icusu.sivan.domain.task;

/**
 * 任务固有特征五维值对象。
 * 用于特征驱动的本能模板匹配和执行路径判定。
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
}
