package com.icusu.sivan.infra.forest.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.Mode;
import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.domain.forest.Forest;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import com.icusu.sivan.domain.forest.tree.*;
import com.icusu.sivan.infra.forest.entity.ForestEntity;
import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import com.icusu.sivan.infra.forest.repository.ForestJpaRepository;
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

    private final ForestJpaRepository forestJpaRepository;
    private final ForestNodeJpaRepository forestNodeJpaRepository;
    private final ObjectMapper objectMapper;

    public ForestRepositoryAdapter(ForestJpaRepository forestJpaRepository,
                                   ForestNodeJpaRepository forestNodeJpaRepository,
                                   ObjectMapper objectMapper) {
        this.forestJpaRepository = forestJpaRepository;
        this.forestNodeJpaRepository = forestNodeJpaRepository;
        this.objectMapper = objectMapper;
    }

    // =====================================================================
    // Forest 聚合根
    // =====================================================================

    @Override
    public Forest findForestById(UUID forestId, UUID accountId) {
        var entity = forestJpaRepository.findByForestIdAndAccountId(forestId, accountId)
                .orElse(null);
        if (entity == null) return null;
        return toDomain(entity);
    }

    @Override
    public void saveForest(Forest forest, UUID accountId) {
        var entity = toEntity(forest);
        forestJpaRepository.save(entity);
        forestJpaRepository.flush();
    }

    @Override
    public List<Forest> findByConversationId(UUID conversationId, UUID accountId) {
        return forestJpaRepository.findByConversationIdOrderByCreatedAtDesc(conversationId)
                .stream()
                .filter(e -> e.getAccountId().equals(accountId))
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Forest> listByAccountId(UUID accountId) {
        return forestJpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    // =====================================================================
    // TreeNode 子树
    // =====================================================================

    @Override
    public TreeNode findSubtree(String rootNodeId, UUID accountId) {
        // 从 forests 找到 forestId
        var forestOpt = forestJpaRepository.findByRootNodeIdAndAccountId(rootNodeId, accountId);
        if (forestOpt.isEmpty()) {
            log.debug("findSubtree: 未找到 rootNodeId={} 对应的 forest", rootNodeId);
            return null;
        }
        UUID forestId = forestOpt.get().getForestId();

        // 递归 CTE 加载所有子孙节点
        List<ForestNodeEntity> rows = forestNodeJpaRepository.findSubtree(rootNodeId, forestId);
        if (rows.isEmpty()) return null;

        return assembleTree(rows, forestId);
    }

    @Override
    public void saveNode(TreeNode node, UUID forestId, UUID accountId) {
        var entity = toEntity(node, forestId);
        forestNodeJpaRepository.save(entity);
        forestNodeJpaRepository.flush();
    }

    @Override
    @Transactional
    public void saveTree(TreeNode root, UUID forestId, UUID accountId) {
        // 删除旧子树（如有），再批量插入
        forestNodeJpaRepository.deleteChildren(forestId, root.nodeId());

        List<ForestNodeEntity> entities = new ArrayList<>();
        collectNodes(root, forestId, entities);
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
            case "message" -> {
                var node = new MessageNode(nodeId, content != null ? content : "");
                if (!metadata.isEmpty()) node.setMetadata(metadata);
                yield node;
            }
            case "memory" -> {
                double importance = row.getImportance() != null ? row.getImportance() : 0.5;
                var node = new MemoryNode(nodeId, content != null ? content : "", importance);
                if (row.getEstimateTokens() != null) node.estimateSubtreeTokens(row.getEstimateTokens());
                if (!metadata.isEmpty()) node.setMetadata(metadata);
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
    private void collectNodes(TreeNode node, UUID forestId, List<ForestNodeEntity> result) {
        result.add(toEntity(node, forestId));
        for (TreeNode child : node.children()) {
            collectNodes(child, forestId, result);
        }
    }

    /** 领域 Forest → JPA 实体。 */
    private ForestEntity toEntity(Forest domain) {
        ForestEntity entity = new ForestEntity();
        entity.setForestId(domain.forestId());
        entity.setAccountId(domain.accountId());
        entity.setProjectId(domain.projectId());
        entity.setConversationId(domain.conversationId());
        entity.setTitle(domain.title());
        entity.setRootNodeId(domain.rootNodeId());
        return entity;
    }

    /** JPA 实体 → 领域 Forest。 */
    private Forest toDomain(ForestEntity entity) {
        return new Forest(
                entity.getForestId(),
                entity.getAccountId(),
                entity.getProjectId(),
                entity.getConversationId(),
                entity.getTitle(),
                entity.getRootNodeId()
        );
    }

    /** TreeNode → JPA 实体。 */
    private ForestNodeEntity toEntity(TreeNode node, UUID forestId) {
        OffsetDateTime now = OffsetDateTime.now();
        var builder = ForestNodeEntity.builder()
                .nodeId(node.nodeId())
                .forestId(forestId)
                .nodeType(node.nodeType())
                .parentNodeId(node.parent() != null ? node.parent().nodeId() : null)
                .sortOrder(node.order())
                .kind("INSTANCE")
                .updatedAt(now);

        // ExecutableNode → mode + status + completedAt
        if (node instanceof ExecutableNode exec) {
            builder.mode(exec.mode().name());
            builder.status(exec.status().name());
            if (exec.status() == NodeStatus.COMPLETED
                    || exec.status() == NodeStatus.FAILED
                    || exec.status() == NodeStatus.CANCELLED) {
                builder.completedAt(now);
            }
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
            // 收集 metadata（含 FileSnapshotNode 的 filePath）
            Map<String, Object> meta = new HashMap<>();
            if (contentNode.metadata() != null) {
                meta.putAll(contentNode.metadata());
            }
            if (node instanceof FileSnapshotNode fsn && fsn.filePath() != null && !fsn.filePath().isEmpty()) {
                meta.put("filePath", fsn.filePath());
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
