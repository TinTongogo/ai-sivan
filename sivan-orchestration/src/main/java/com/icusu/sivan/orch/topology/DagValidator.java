package com.icusu.sivan.orch.topology;

import com.icusu.sivan.domain.orchestration.PhaseNode;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * DAG 图验证器。在 {@link TopologyGenerator} 生成拓扑后、执行前调用。
 *
 * <p>四项验证：
 * <ol>
 *   <li>索引有效性 — dependsOn 引用的阶段索引必须存在</li>
 *   <li>环检测（Kahn 算法） — 有环则图不可执行</li>
 *   <li>连通性检查 — 所有节点均从根节点可达</li>
 *   <li>CONDITIONAL sink 验证 — 至少存在一条路径到达终止</li>
 * </ol>
 */
public class DagValidator {

    /**
     * 全量验证。抛出 {@link DagValidationException} 描述首个失败。
     */
    public static void validate(List<PhaseNode> phases) {
        if (phases == null || phases.isEmpty()) return;

        validateIndices(phases);
        detectCycle(phases);
        checkConnectivity(phases);
        // CONDITIONAL sink 仅在存在 conditions 时验证
        if (hasConditionalPhases(phases)) {
            validateConditionalSink(phases);
        }
    }

    // ==================== 索引有效性 ====================

    /**
     * dependsOn 中的索引必须 ∈ [0, phases.size())。
     */
    public static void validateIndices(List<PhaseNode> phases) {
        int size = phases.size();
        for (int i = 0; i < size; i++) {
            List<Integer> deps = phases.get(i).getDependsOn();
            if (deps != null) {
                for (int dep : deps) {
                    if (dep < 0 || dep >= size) {
                        throw new DagValidationException(
                                "阶段 " + i + " 的 dependsOn 引用了不存在的阶段索引 " + dep
                                        + "（有效范围: 0~" + (size - 1) + "）");
                    }
                    if (dep == i) {
                        throw new DagValidationException("阶段 " + i + " 的 dependsOn 不能自引用");
                    }
                }
            }
        }
    }

    // ==================== 环检测 ====================

    /**
     * Kahn 算法拓扑排序，有环时抛出异常。
     */
    public static void detectCycle(List<PhaseNode> phases) {
        int n = phases.size();
        // 邻接表 + 入度表
        List<List<Integer>> adjacency = new ArrayList<>(n);
        int[] inDegree = new int[n];
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++) {
            List<Integer> deps = phases.get(i).getDependsOn();
            if (deps != null) {
                for (int dep : deps) {
                    adjacency.get(dep).add(i);
                    inDegree[i]++;
                }
            }
        }

        // Kahn: 入度为 0 的节点入队
        Queue<Integer> queue = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) queue.add(i);
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            visited++;
            for (int neighbor : adjacency.get(node)) {
                inDegree[neighbor]--;
                if (inDegree[neighbor] == 0) queue.add(neighbor);
            }
        }

        if (visited != n) {
            // 找出环中的节点
            List<Integer> cycleNodes = IntStream.range(0, n)
                    .filter(i -> inDegree[i] > 0)
                    .boxed()
                    .collect(Collectors.toList());
            throw new DagValidationException(
                    "阶段拓扑中存在环，涉及阶段索引: " + cycleNodes
                            + "（" + (n - visited) + " 个节点无法拓扑排序）");
        }
    }

    // ==================== 连通性 ====================

    /**
     * 所有节点必须从某个根节点（dependsOn=null 或 dependsOn=[]）可达。
     */
    public static void checkConnectivity(List<PhaseNode> phases) {
        int n = phases.size();
        // 反向邻接表: 谁依赖我
        List<List<Integer>> reverseAdj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            reverseAdj.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++) {
            List<Integer> deps = phases.get(i).getDependsOn();
            if (deps != null) {
                for (int dep : deps) {
                    reverseAdj.get(dep).add(i);
                }
            }
        }

        // 从根节点出发 BFS
        boolean[] reachable = new boolean[n];
        Queue<Integer> queue = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            List<Integer> deps = phases.get(i).getDependsOn();
            if (deps == null || deps.isEmpty()) {
                queue.add(i);
                reachable[i] = true;
            }
        }

        if (queue.isEmpty() && n > 0) {
            // 理论上已由环检测捕获，但以防万一
            throw new DagValidationException("所有阶段都有依赖，没有根节点");
        }

        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (int neighbor : reverseAdj.get(node)) {
                if (!reachable[neighbor]) {
                    reachable[neighbor] = true;
                    queue.add(neighbor);
                }
            }
        }

        // 检查不可达节点
        List<Integer> unreachable = IntStream.range(0, n)
                .filter(i -> !reachable[i])
                .boxed()
                .collect(Collectors.toList());
        if (!unreachable.isEmpty()) {
            throw new DagValidationException(
                    "以下阶段从根节点不可达: " + unreachable
                            + "（存在 dependsOn 孤岛或依赖缺失）");
        }
    }

    // ==================== CONDITIONAL sink ====================

    /**
     * CONDITIONAL 模式下，至少有一条路径能到达终止（即存在无出边的节点或 -1 终止条件）。
     */
    public static void validateConditionalSink(List<PhaseNode> phases) {
        int n = phases.size();
        // 构建正向邻接表（从 dependsOn 推断）
        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            List<Integer> deps = phases.get(i).getDependsOn();
            if (deps != null) {
                for (int dep : deps) {
                    adjacency.get(dep).add(i);
                }
            }
        }

        // 找 sink 节点（无出边）
        boolean hasSink = false;
        for (int i = 0; i < n; i++) {
            if (adjacency.get(i).isEmpty()) {
                hasSink = true;
                break;
            }
        }
        if (!hasSink) {
            throw new DagValidationException(
                    "CONDITIONAL 模式没有终止阶段：所有阶段都有出边，图可能无限循环");
        }
    }

    // ==================== 工具 ====================

    private static boolean hasConditionalPhases(List<PhaseNode> phases) {
        return phases.stream().anyMatch(p -> p.getConditions() != null && !p.getConditions().isEmpty());
    }

    /**
     * DAG 验证异常。
     */
    public static class DagValidationException extends RuntimeException {
        public DagValidationException(String message) {
            super(message);
        }
    }
}
