package com.icusu.sivan.domain.context;

/**
 * 上下文树接口，森林架构中每种内容类型的统一抽象。
 * <p>
 * 每种内容类型（对话、Squad、知识库、记忆、工具链）实现此接口，
 * ContextBuilder 遍历森林中各树做预算分配和折叠决策。
 */
public interface ContextTree {

    /** 树类型标识。 */
    String treeType();

    /**
     * 构建上下文文本，按场景和预算控制折叠粒度。
     *
     * @param scene     当前场景（影响折叠策略和优先级）
     * @param maxTokens 本树分配的 token 预算
     * @return 格式化上下文文本，空字符串表示无需注入
     */
    String buildContext(String scene, int maxTokens);

    /** 估算当前树的 token 占用。 */
    int estimateTokens();
}
