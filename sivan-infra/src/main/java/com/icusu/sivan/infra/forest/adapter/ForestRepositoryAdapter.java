package com.icusu.sivan.infra.forest.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.tree.*;
import com.icusu.sivan.domain.forest.tree.node.*;
import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Forest 仓储适配器 — 基于 JPA + 递归 CTE 的完整实现。
 * <p>
 * 树结构使用 parent_node_id 外键自引用 + 递归 CTE 加载。
 */
@Component
public class ForestRepositoryAdapter implements ForestRepository {

    private static final Logger log = LoggerFactory.getLogger(ForestRepositoryAdapter.class);

    /** 运行时 metadata key 黑名单 — 这些 key 仅在内存中使用，禁止持久化到 JSONB。
     * 仅限大对象和运行时标记，显示需要的元数据（agentName、routeTier 等）应保留。 */
    private static final java.util.Set<String> RUNTIME_METADATA_KEYS = java.util.Set.of(
            "prebuiltMessages", "preferredToolSpecs",
            "_isSequentialSubtask", "accumulatedContext", "peers");

    private final ForestNodeJpaRepository forestNodeJpaRepository;
    private final ObjectMapper objectMapper;

    public ForestRepositoryAdapter(ForestNodeJpaRepository forestNodeJpaRepository,
                                   ObjectMapper objectMapper) {
        this.forestNodeJpaRepository = forestNodeJpaRepository;
        this.objectMapper = objectMapper;
    }

    // =====================================================================
    // Forest 聚合根
    // =====================================================================

    @Override
    public Forest findForestById(UUID forestId, UUID accountId) {
        // 从 forest_nodes 查找该森林的第一个节点（根节点），提取元数据
        var roots = forestNodeJpaRepository.findByForestIdAndNodeTypeOrderBySortOrder(forestId, "task");
        if (roots.isEmpty()) {
            roots = forestNodeJpaRepository.findByForestIdAndNodeTypeOrderBySortOrder(forestId, "inner_goal");
        }
        if (roots.isEmpty()) {
            // 可能是对话容器节点
            var convNode = forestNodeJpaRepository.findById(forestId.toString())
                    .filter(e -> "conversation".equals(e.getNodeType()))
                    .orElse(null);
            if (convNode == null) return null;
            return new Forest(forestId, convNode.getAccountId(), convNode.getProjectId(),
                    convNode.getContent(), "");
        }
        var root = roots.getFirst();
        return new Forest(forestId, root.getAccountId(), root.getProjectId(),
                root.getContent(), root.getNodeId());
    }

    @Override
    public void saveForest(Forest forest, UUID accountId) {
        // 森林元数据由 rootNode 承载，不需单独存储
        // rootNodeId 在 saveTree 时写入节点，森林的结构数据全在 forest_nodes 中
        log.debug("saveForest: forestId={} title={} root={}", forest.forestId(), forest.title(), forest.rootNodeId());
    }

    @Override
    public List<Forest> findByConversationId(UUID conversationId, UUID accountId) {
        // conversations 已迁到 forest_nodes，不再使用 forests 表
        return List.of();
    }

    @Override
    public List<Forest> listByAccountId(UUID accountId) {
        return forestNodeJpaRepository.findForestRootsByAccount(accountId).stream()
                .map(n -> new Forest(n.getForestId(), n.getAccountId(), n.getProjectId(),
                        n.getContent() != null ? n.getContent() : "", n.getNodeId()))
                .toList();
    }

    // =====================================================================
    // TreeNode 子树
    // =====================================================================

    @Override
    public TreeNode findSubtree(String rootNodeId, UUID accountId) {
        if (rootNodeId == null || rootNodeId.isBlank()) {
            log.debug("findSubtree: rootNodeId 为空，无子树可加载");
            return null;
        }
        // 直接查找 rootNodeId 所在的 forestId
        UUID forestId = null;
        var rootNodeOpt = forestNodeJpaRepository.findById(rootNodeId);
        if (rootNodeOpt.isPresent()) {
            forestId = rootNodeOpt.get().getForestId();
        }
        if (forestId == null) {
            log.debug("findSubtree: 未找到 rootNodeId={}", rootNodeId);
            return null;
        }
        // 递归 CTE 加载所有子孙节点
        List<ForestNodeEntity> rows = forestNodeJpaRepository.findSubtree(rootNodeId, forestId);
        if (rows.isEmpty()) return null;

        return assembleTree(rows, forestId);
    }

    @Override
    public void saveNode(TreeNode node, UUID forestId, UUID accountId) {
        var entity = toEntity(node, forestId, accountId);
        forestNodeJpaRepository.save(entity);
        forestNodeJpaRepository.flush();
    }

    @Override
    @Transactional
    public void saveTree(TreeNode root, UUID forestId, UUID accountId) {
        // 删除旧子树（如有），再批量插入
        forestNodeJpaRepository.deleteChildren(forestId, root.nodeId());

        List<ForestNodeEntity> entities = new ArrayList<>();
        collectNodes(root, forestId, entities, accountId);
        forestNodeJpaRepository.saveAll(entities);
        forestNodeJpaRepository.flush();
    }

    @Override
    public void updateNodeStatus(String nodeId, NodeStatus status, UUID accountId) {
        forestNodeJpaRepository.findById(nodeId).ifPresent(entity -> {
            OffsetDateTime now = OffsetDateTime.now();
            entity.setStatus(status.name());
            entity.setUpdatedAt(now);
            if (status == NodeStatus.COMPLETED
                    || status == NodeStatus.FAILED
                    || status == NodeStatus.CANCELLED) {
                entity.setCompletedAt(now);
            }
            forestNodeJpaRepository.save(entity);
            forestNodeJpaRepository.flush();
        });
    }

    @Override
    public void updateNodeDetails(String nodeId, NodeStatus status, UUID accountId,
                                   Integer durationMs, Integer totalTokens) {
        forestNodeJpaRepository.findById(nodeId).ifPresent(entity -> {
            OffsetDateTime now = OffsetDateTime.now();
            entity.setStatus(status.name());
            entity.setUpdatedAt(now);
            if (status == NodeStatus.COMPLETED
                    || status == NodeStatus.FAILED
                    || status == NodeStatus.CANCELLED) {
                entity.setCompletedAt(now);
            }
            // estimateTokens 列复用为实际 token 消耗
            if (totalTokens != null && totalTokens > 0) {
                entity.setEstimateTokens(totalTokens.longValue());
            }
            forestNodeJpaRepository.save(entity);
            forestNodeJpaRepository.flush();
        });
    }

    @Override
    public List<TreeNode> findRootNodesByStatus(NodeStatus status, UUID accountId) {
        List<ForestNodeEntity> roots = forestNodeJpaRepository.findRootNodesByStatus(
                status.name(), accountId);
        return roots.stream()
                .map(this::createNode)
                .toList();
    }

    @Override
    public TreeNode findNextSibling(String nodeId, UUID forestId, UUID accountId) {
        var currentOpt = forestNodeJpaRepository.findById(nodeId);
        if (currentOpt.isEmpty()) return null;
        ForestNodeEntity current = currentOpt.get();
        String parentNodeId = current.getParentNodeId();
        if (parentNodeId == null) return null; // 根节点无兄弟

        ForestNodeEntity next = forestNodeJpaRepository.findNextPendingSibling(
                parentNodeId, forestId, current.getSortOrder());
        if (next == null) return null;
        return createNode(next);
    }

    // =====================================================================
    // 树组装
    // =====================================================================

    /**
     * 将扁平的行列表组装为树结构。
     * 两遍扫描：先创建节点，再连接父子关系。
     */
    private TreeNode assembleTree(List<ForestNodeEntity> rows, UUID forestId) {
        Map<String, TreeNode> nodeMap = new LinkedHashMap<>();
        Map<String, List<String>> parentToChildren = new HashMap<>();

        for (var row : rows) {
            TreeNode node = createNode(row);
            nodeMap.put(row.getNodeId(), node);
            if (row.getParentNodeId() != null) {
                parentToChildren.computeIfAbsent(row.getParentNodeId(), k -> new ArrayList<>())
                        .add(row.getNodeId());
            }
        }

        // 连接父子关系
        for (var entry : parentToChildren.entrySet()) {
            TreeNode parent = nodeMap.get(entry.getKey());
            if (parent instanceof InnerGoalNode innerGoal) {
                List<TreeNode> children = entry.getValue().stream()
                        .map(nodeMap::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                for (int i = 0; i < children.size(); i++) {
                    children.get(i).setParent(parent);
                    children.get(i).setOrder(i);
                }
                innerGoal.replaceChildren(children);
            }
        }

        // 返回根节点（第一条 = rootNodeId 所在行）
        return nodeMap.values().stream().findFirst().orElse(null);
    }

    /**
     * 根据行数据构造对应的 TreeNode 子类实例。
     */
    @SuppressWarnings("unchecked")
    private TreeNode createNode(ForestNodeEntity row) {
        String type = row.getNodeType();
        String nodeId = row.getNodeId();
        NodeStatus status = row.getStatus() != null ? NodeStatus.valueOf(row.getStatus()) : NodeStatus.PENDING;
        Mode mode = row.getMode() != null ? Mode.valueOf(row.getMode()) : Mode.NONE;
        Map<String, Object> metadata = parseMetadata(row.getMetadata());
        String content = row.getContent();

        return switch (type) {
            case "task" -> {
                var node = new TaskNode(nodeId, content != null ? content : "", status);
                if (row.getImportance() != null) node.importance(row.getImportance());
                if (row.getEstimateTokens() != null) node.estimateSubtreeTokens(row.getEstimateTokens());
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            case "inner_goal" -> {
                // children 由 assembleTree 后续通过 replaceChildren 注入
                var node = new InnerGoalNode(nodeId, mode, List.of(), status);
                if (row.getImportance() != null) node.importance(row.getImportance());
                if (row.getEstimateTokens() != null) node.estimateSubtreeTokens(row.getEstimateTokens());
                yield node;
            }
            case "synthesis" -> {
                var node = new SynthesisNode(nodeId, content != null ? content : "", status);
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            case "user_message" -> {
                // 用户消息作为执行树根，用 TaskNode 使其可承载子节点
                var node = new TaskNode(nodeId, content != null ? content : "",
                        row.getStatus() != null ? NodeStatus.valueOf(row.getStatus()) : NodeStatus.COMPLETED);
                node.setNodeType("user_message");
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                if (row.getRole() != null) node.metadata().put("role", row.getRole());
                yield node;
            }
            case "message" -> {
                var node = new MessageNode(nodeId, content != null ? content : "", row.getRole());
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            case "memory" -> {
                double retention = row.getRetention() != null ? row.getRetention().doubleValue() : 0.5;
                var node = new MemoryNode(nodeId, content != null ? content : "", retention);
                // 先用 JSONB 填充 metadata（兼容旧数据）
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                // 再用专用列覆盖（V30 新增列优先于 JSONB）
                if (row.getLevel() != null) node.metadata().put("level", row.getLevel());
                if (row.getArchived() != null) node.metadata().put("archived", row.getArchived());
                if (row.getImportant() != null) node.metadata().put("important", row.getImportant());
                if (row.getScopeId() != null) node.metadata().put("scopeId", row.getScopeId().toString());
                if (row.getSummary() != null) node.metadata().put("summary", row.getSummary());
                // accessCount 保留在 JSONB 中且可能被 ForgettingCurveService 写入，保持兼容
                if (row.getVector() != null) node.addVector(row.getVector());
                yield node;
            }
            case "file_snapshot" -> {
                String filePath = metadata.containsKey("filePath")
                        ? String.valueOf(metadata.get("filePath")) : "";
                var node = new FileSnapshotNode(nodeId, filePath, content != null ? content : "");
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            case "context_block" -> {
                String blockType = metadata.containsKey("blockType")
                        ? String.valueOf(metadata.get("blockType")) : "";
                var node = new ContextBlockNode(nodeId, blockType, content != null ? content : "");
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            case "kb_search" -> {
                var node = new SearchKBNode(nodeId, content != null ? content : "",
                        metadata.containsKey("kbName") ? String.valueOf(metadata.get("kbName")) : null,
                        metadata.containsKey("topK") ? Integer.parseInt(String.valueOf(metadata.get("topK"))) : 5);
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            default -> throw new IllegalArgumentException("未知节点类型: " + type);
        };
    }

    // =====================================================================
    // 收集 / 转换
    // =====================================================================

    /** 递归收集子树所有节点到扁平列表。 */
    private void collectNodes(TreeNode node, UUID forestId, List<ForestNodeEntity> result, UUID accountId) {
        result.add(toEntity(node, forestId, accountId));
        for (TreeNode child : node.children()) {
            collectNodes(child, forestId, result, accountId);
        }
    }


    /** TreeNode → JPA 实体。 */
    private ForestNodeEntity toEntity(TreeNode node, UUID forestId, UUID accountId) {
        OffsetDateTime now = OffsetDateTime.now();
        var builder = ForestNodeEntity.builder()
                .nodeId(node.nodeId())
                .forestId(forestId)
                .nodeType(node.nodeType())
                .parentNodeId(resolveParentNodeId(node, forestId))
                .sortOrder(node.order())
                .kind("INSTANCE")
                .accountId(accountId)
                .updatedAt(now);

        // ExecutableNode → mode + status + completedAt
        builder.mode(node.mode().name());
        builder.status(node.status().name());
        if (node.status() == NodeStatus.COMPLETED
                || node.status() == NodeStatus.FAILED
                || node.status() == NodeStatus.CANCELLED) {
            builder.completedAt(now);
        }

        // MessageNode → role
        if (node instanceof MessageNode mn) {
            builder.role(mn.role());
        }

        // ContentNode → content + contentHash + metadata
        if (node instanceof ContentNode contentNode) {
            String content = contentNode.content();
            builder.content(content);
            if (content != null && !content.isEmpty()) {
                // contentHash 用于增量更新判断：SHA256 前 16 位
                builder.contentHash(computeContentHash(content));
                // estimateTokens 基于内容长度估算（每中文字符约 2 token）
                long estimated = (long) (content.length() * 1.5);
                if (estimated > 0) builder.estimateTokens(estimated);
            }
            // 收集 metadata（过滤运行时 key，补充节点特有字段的序列化）
            Map<String, Object> meta = new HashMap<>();
            if (contentNode.metadata() != null) {
                for (var entry : contentNode.metadata().entrySet()) {
                    if (!RUNTIME_METADATA_KEYS.contains(entry.getKey())) {
                        meta.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            // FileSnapshotNode.filePath → 补充写入 metadata（字段值优先于已有 meta）
            if (node instanceof FileSnapshotNode fsn && fsn.filePath() != null && !fsn.filePath().isEmpty()) {
                meta.put("filePath", fsn.filePath());
            }
            // ContextBlockNode.blockType → 补充写入 metadata（防止 persist 后丢失）
            if (node instanceof ContextBlockNode cbn && cbn.blockType() != null && !cbn.blockType().isEmpty()) {
                meta.put("blockType", cbn.blockType());
            }
            if (!meta.isEmpty()) {
                try {
                    builder.metadata(objectMapper.writeValueAsString(meta));
                } catch (Exception e) {
                    log.warn("序列化 metadata 失败: {}", e.getMessage());
                }
            }
        }

        // CompressibleNode → importance（estimateTokens 已在 ContentNode 中处理）
        if (node instanceof CompressibleNode comp) {
            builder.importance(comp.importance());
        }

        return builder.build();
    }

    /**
     * 解析父节点 ID：
     * - 树节点有 parent → 用 parent.nodeId()
     * - 无 parent 的顶层节点 → 挂接到对话容器节点（forestId 对应的 type='conversation' 节点）
     *   （对话容器节点 node_id = forestId.toString()，已由 ConversationRepositoryAdapter 创建）
     */
    private static String resolveParentNodeId(TreeNode node, UUID forestId) {
        if (node.parent() != null) return node.parent().nodeId();
        // 顶层节点挂接到对话容器
        return forestId.toString();
    }

    /** 计算内容哈希（SHA256 前 16 位），用于增量更新判断。 */
    private static String computeContentHash(String content) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    @Override
    public List<? extends TreeNode> findNodesByType(UUID forestId, String nodeType, UUID accountId) {
        var entities = forestNodeJpaRepository.findByForestIdAndNodeTypeOrderBySortOrder(forestId, nodeType);
        if (entities == null || entities.isEmpty()) return List.of();
        return entities.stream()
                .map(this::createNode)
                .toList();
    }

    @Override
    public void updateNodeContent(String nodeId, String content, Map<String, Object> metadata, UUID accountId) {
        forestNodeJpaRepository.findById(nodeId).ifPresent(entity -> {
            entity.setContent(content);
            if (metadata != null && !metadata.isEmpty()) {
                try {
                    String existingJson = entity.getMetadata();
                    java.util.Map<String, Object> existing = existingJson != null && !existingJson.isBlank() && !"{}".equals(existingJson)
                            ? objectMapper.readValue(existingJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {})
                            : new java.util.HashMap<>();
                    existing.putAll(metadata);
                    entity.setMetadata(objectMapper.writeValueAsString(existing));
                } catch (Exception e) {
                    log.warn("合并 metadata 失败: {}", e.getMessage());
                }
            }
            entity.setUpdatedAt(java.time.OffsetDateTime.now());
            forestNodeJpaRepository.save(entity);
        });
    }

    @Override
    public List<? extends TreeNode> findNodesByTypeAndAccount(UUID accountId, String nodeType, int limit) {
        var entities = forestNodeJpaRepository.findByNodeTypeAndStatusOrderBySortOrder(nodeType, null);
        if (entities == null || entities.isEmpty()) return List.of();
        return entities.stream().map(this::createNode).toList();
    }

    @Override
    public List<? extends TreeNode> semanticSearchMemory(UUID accountId, float[] queryVec, int topK, String levelFilter) {
        if (queryVec == null) return List.of();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < queryVec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(queryVec[i]);
        }
        sb.append("]");
        var entities = forestNodeJpaRepository.semanticSearchMemory("memory", sb.toString(), topK);
        return entities.stream().map(this::createNode).toList();
    }

    @Override
    public long countActiveMemories(UUID accountId) {
        return forestNodeJpaRepository.countByNodeType("memory");
    }

    @Override
    public void updateMemoryRetention(String nodeId, double retention, UUID accountId) {
        forestNodeJpaRepository.findById(nodeId).ifPresent(entity -> {
            entity.setRetention(java.math.BigDecimal.valueOf(retention));
            entity.setUpdatedAt(java.time.OffsetDateTime.now());
            forestNodeJpaRepository.save(entity);
        });
    }

    @Override
    @Transactional
    public void deleteForest(UUID forestId, UUID accountId) {
        forestNodeJpaRepository.deleteByForestId(forestId);
        log.debug("deleteForest: 已删除森林节点: forestId={}", forestId);
    }

    /** 解析 metadata JSON → Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析 metadata JSON 失败: {}", e.getMessage());
            return Map.of();
        }
    }
}
