package com.icusu.sivan.domain.context;

import java.util.*;

/**
 * 森林容器，管理多种内容类型的上下文树。
 * <p>
 * 对话树、Squad 树、知识库树、记忆树、工具链树统一注册到此容器，
 * ContextBuilder 遍历其中各树做预算分配和折叠决策。
 */
public class ContextForest {

    public static final String SCENE_CHAT = "CHAT";
    public static final String SCENE_SQUAD = "SQUAD_ACTIVE";
    public static final String SCENE_KB = "KB_SEARCH";
    public static final String SCENE_TOOL = "TOOL_HEAVY";

    private final Map<String, ContextTree> trees = new LinkedHashMap<>();

    /** 注册树。同类型覆盖。 */
    public ContextForest register(ContextTree tree) {
        trees.put(tree.treeType(), tree);
        return this;
    }

    /** 获取指定类型的树。 */
    @SuppressWarnings("unchecked")
    public <T extends ContextTree> T get(String treeType) {
        return (T) trees.get(treeType);
    }

    /** 所有已注册的树。 */
    public Collection<ContextTree> all() {
        return Collections.unmodifiableCollection(trees.values());
    }

    /** 树数量。 */
    public int size() {
        return trees.size();
    }

    /** 清空。 */
    public void clear() {
        trees.clear();
    }

    /**
     * 按场景构建各树上下文并汇总。
     *
     * @param scene     当前场景（影响折叠策略和优先级）
     * @param maxTokens 总 token 预算
     * @return 各树的上下文拼接文本
     */
    public String buildAll(String scene, int maxTokens) {
        if (trees.isEmpty()) return "";

        // 按场景分配各树预算
        Map<String, Integer> budgetMap = allocateBudget(scene, maxTokens);

        StringBuilder sb = new StringBuilder();
        for (ContextTree tree : trees.values()) {
            int budget = budgetMap.getOrDefault(tree.treeType(), 0);
            if (budget <= 0) continue;
            String context = tree.buildContext(scene, budget);
            if (context != null && !context.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(context);
            }
        }
        return sb.toString();
    }

    /**
     * 按场景和树类型分配 token 预算。
     * CHAT: 对话树占 70%，其余 30% 平分
     * SQUAD: Squad 树占 50%，对话树 30%，其余 20% 平分
     * KB: 知识库树占 60%，对话树 25%，其余 15% 平分
     * TOOL: 工具链树占 50%，对话树 30%，其余 20% 平分
     */
    private Map<String, Integer> allocateBudget(String scene, int maxTokens) {
        Map<String, Integer> result = new HashMap<>();
        if (trees.isEmpty()) return result;

        switch (scene) {
            case SCENE_CHAT -> {
                result.put("conversation", (int) (maxTokens * 0.70));
                int remaining = maxTokens - result.get("conversation");
                int otherCount = trees.size() - 1;
                if (otherCount > 0) {
                    int perOther = remaining / otherCount;
                    for (ContextTree t : trees.values()) {
                        if (!"conversation".equals(t.treeType())) {
                            result.put(t.treeType(), perOther);
                        }
                    }
                }
            }
            case SCENE_SQUAD -> {
                result.put("squad", (int) (maxTokens * 0.50));
                result.put("conversation", (int) (maxTokens * 0.30));
                int remaining = maxTokens - result.get("squad") - result.get("conversation");
                int otherCount = trees.size() - 2;
                if (otherCount > 0) {
                    int perOther = remaining / otherCount;
                    for (ContextTree t : trees.values()) {
                        if (!"squad".equals(t.treeType()) && !"conversation".equals(t.treeType())) {
                            result.put(t.treeType(), perOther);
                        }
                    }
                }
            }
            case SCENE_KB -> {
                result.put("kb", (int) (maxTokens * 0.60));
                result.put("conversation", (int) (maxTokens * 0.25));
                int remaining = maxTokens - result.get("kb") - result.get("conversation");
                int otherCount = trees.size() - 2;
                if (otherCount > 0) {
                    int perOther = remaining / otherCount;
                    for (ContextTree t : trees.values()) {
                        if (!"kb".equals(t.treeType()) && !"conversation".equals(t.treeType())) {
                            result.put(t.treeType(), perOther);
                        }
                    }
                }
            }
            case SCENE_TOOL -> {
                result.put("toolchain", (int) (maxTokens * 0.50));
                result.put("conversation", (int) (maxTokens * 0.30));
                int remaining = maxTokens - result.get("toolchain") - result.get("conversation");
                int otherCount = trees.size() - 2;
                if (otherCount > 0) {
                    int perOther = remaining / otherCount;
                    for (ContextTree t : trees.values()) {
                        if (!"toolchain".equals(t.treeType()) && !"conversation".equals(t.treeType())) {
                            result.put(t.treeType(), perOther);
                        }
                    }
                }
            }
            default -> {
                int perTree = maxTokens / trees.size();
                for (ContextTree t : trees.values()) {
                    result.put(t.treeType(), perTree);
                }
            }
        }
        return result;
    }

    /** 估算所有树的 token 总数。 */
    public int estimateTotalTokens() {
        return trees.values().stream().mapToInt(ContextTree::estimateTokens).sum();
    }
}
