package com.icusu.sivan.infra.forest.compression;

import com.icusu.sivan.domain.compression.FoldDecision;
import com.icusu.sivan.domain.compression.FoldStrategy;
import com.icusu.sivan.domain.compression.TokenBudget;
import com.icusu.sivan.domain.compression.TokenBudgetManager;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.tree.CompressibleNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 森林压缩器 — 遍历每棵树，按预算对超过预算的子树做折叠/摘要/裁剪。
 * <p>
 * 委派到各 {@link FoldStrategy} 执行具体节点类型的折叠决策。
 */
@Component
public class ForestCompressor {

    private static final Logger log = LoggerFactory.getLogger(ForestCompressor.class);

    private final Map<String, FoldStrategy> strategies;
    private final TokenBudgetManager budgetManager;

    public ForestCompressor(List<FoldStrategy> strategyList, TokenBudgetManager budgetManager) {
        this.strategies = strategyList.stream().collect(Collectors.toMap(FoldStrategy::supportedType, s -> s));
        this.budgetManager = budgetManager;
    }

    /**
     * 压缩森林 — 遍历每棵树，对超出预算的子树执行折叠/摘要/裁剪。
     *
     * @param forest    森林聚合根
     * @param root      树的根节点
     * @param scene     压缩场景（如 "view" / "edit" / "send"）
     * @param maxTokens 最大 token 预算
     * @return 压缩后的树根节点（就地修改）
     */
    public TreeNode compress(Forest forest, TreeNode root, String scene, int maxTokens) {
        if (root == null) return null;

        TokenBudget budget = buildBudget(scene, maxTokens);
        log.debug("[压缩] forestId={} scene={} maxTokens={}", forest.forestId(), scene, maxTokens);

        compressNode(root, budget, 0);
        return root;
    }

    /**
     * 递归压缩节点。
     *
     * @param node   当前节点
     * @param budget token 预算
     * @param depth  当前深度
     */
    private void compressNode(TreeNode node, TokenBudget budget, int depth) {
        // 先递归子节点（深度优先）
        for (TreeNode child : node.children()) {
            compressNode(child, budget, depth + 1);
        }

        // 只处理 CompressibleNode
        if (!(node instanceof CompressibleNode cn)) return;

        // 查找匹配的 FoldStrategy
        FoldStrategy strategy = strategies.get(node.nodeType());
        if (strategy == null) {
            log.trace("[压缩] 无匹配策略: nodeType={}", node.nodeType());
            return;
        }

        // 执行折叠决策
        FoldDecision decision = strategy.decide(node, budget);
        if (decision.shouldFold()) {
            log.debug("[压缩] 折叠: nodeId={} type={} reason={}",
                    node.nodeId(), node.nodeType(), decision.reason());
            // 标记节点的 token 缓存失效
            cn.invalidateTokenCache();
        }
    }

    /**
     * 根据场景和总预算构建各类型的分配预算。
     */
    private TokenBudget buildBudget(String scene, int maxTokens) {
        // 通过 TokenBudgetManager 按场景分配预算
        Map<String, Integer> allocation = budgetManager.allocate(scene, maxTokens);
        return new TokenBudget(maxTokens, allocation);
    }
}
