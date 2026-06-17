package com.icusu.sivan.infra.memory.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.infra.forest.repository.ForestNodeJpaRepository;
import com.icusu.sivan.infra.forest.entity.ForestNodeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 记忆仓储适配器 — 基于 forest_nodes(type='memory') + 专用列的完整实现。
 * <p>
 * 利用新增的 level/archived/important/scope_id 列实现 SQL WHERE 过滤，
 * 替代旧版 JSONB 内存过滤。
 * <p>
 * 写操作同时更新专用列和 metadata JSONB，保证读路径兼容性。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryRepositoryAdapter implements IMemoryRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final ForestNodeJpaRepository forestNodeJpaRepository;

    // =====================================================================
    // 写操作
    // =====================================================================

    @Override
    public UUID save(MemoryEntry entry) {
        UUID id = entry.getMemoryId() != null ? entry.getMemoryId() : UUID.randomUUID();
        try {
            Integer maxSort = forestNodeJpaRepository.findMaxMemorySortOrder().orElse(0);
            int sortOrder = maxSort + 1;

            Map<String, Object> metadata = new LinkedHashMap<>();
            if (entry.getSummary() != null) metadata.put("summary", entry.getSummary());
            metadata.put("level", entry.getLevel().name());
            metadata.put("scopeId", entry.getScopeId());
            metadata.put("accessCount", entry.getAccessCount() != null ? entry.getAccessCount() : 0);
            metadata.put("archived", entry.getArchived() != null && entry.getArchived());
            metadata.put("important", entry.getImportant() != null && entry.getImportant());
            if (entry.getProjectId() != null) metadata.put("projectId", entry.getProjectId().toString());

            ForestNodeEntity entity = ForestNodeEntity.builder()
                    .nodeId(id.toString())
                    .forestId(entry.getScopeId() != null ? UUID.fromString(entry.getScopeId()) : UUID.randomUUID())
                    .nodeType("memory")
                    .accountId(entry.getAccountId())
                    .projectId(entry.getProjectId())
                    .content(entry.getContent() != null ? entry.getContent() : "")
                    .sortOrder(sortOrder)
                    .updatedAt(OffsetDateTime.now())
                    .build();

            // 写入专用列（SQL WHERE 过滤用）
            entity.setLevel(entry.getLevel() != null ? entry.getLevel().name() : MemoryLevel.SESSION.name());
            entity.setScopeId(parseScopeId(entry.getScopeId()));
            entity.setArchived(entry.getArchived() != null && entry.getArchived());
            entity.setImportant(entry.getImportant() != null && entry.getImportant());
            if (entry.getSummary() != null) entity.setSummary(entry.getSummary());

            // 保留 metadata JSONB（兼容旧读路径）
            if (!metadata.isEmpty()) {
                entity.setMetadata(OBJECT_MAPPER.writeValueAsString(metadata));
            }
            if (entry.getVector() != null) entity.setVector(entry.getVector());
            if (entry.getRetention() != null) {
                entity.setRetention(BigDecimal.valueOf(entry.getRetention()));
            }
            if (entry.getLastAccessedAt() != null) {
                entity.setLastAccessedAt(OffsetDateTime.of(entry.getLastAccessedAt(), ZoneOffset.UTC));
            }

            forestNodeJpaRepository.save(entity);
            return id;
        } catch (Exception e) {
            log.warn("保存记忆失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void update(MemoryEntry entry) {
        if (entry.getMemoryId() == null) return;
        forestNodeJpaRepository.findById(entry.getMemoryId().toString()).ifPresent(entity -> {
            if (entry.getContent() != null) entity.setContent(entry.getContent());
            if (entry.getSummary() != null) entity.setSummary(entry.getSummary());
            if (entry.getLevel() != null) entity.setLevel(entry.getLevel().name());
            if (entry.getScopeId() != null) entity.setScopeId(parseScopeId(entry.getScopeId()));
            if (entry.getRetention() != null) entity.setRetention(BigDecimal.valueOf(entry.getRetention()));
            if (entry.getAccessCount() != null) entity.setAccessCount(entry.getAccessCount());
            if (entry.getArchived() != null) entity.setArchived(entry.getArchived());
            if (entry.getImportant() != null) entity.setImportant(entry.getImportant());
            if (entry.getVector() != null) entity.setVector(entry.getVector());
            if (entry.getLastAccessedAt() != null) {
                entity.setLastAccessedAt(OffsetDateTime.of(entry.getLastAccessedAt(), ZoneOffset.UTC));
            }
            entity.setUpdatedAt(OffsetDateTime.now());
            // 同步更新 metadata JSONB（保持兼容性）
            try {
                Map<String, Object> meta = entity.getMetadata() != null && !"{}".equals(entity.getMetadata())
                        ? OBJECT_MAPPER.readValue(entity.getMetadata(), new TypeReference<Map<String, Object>>() {})
                        : new LinkedHashMap<>();
                if (entry.getLevel() != null) meta.put("level", entry.getLevel().name());
                if (entry.getArchived() != null) meta.put("archived", entry.getArchived());
                if (entry.getImportant() != null) meta.put("important", entry.getImportant());
                meta.put("accessCount", entry.getAccessCount() != null ? entry.getAccessCount() : 0);
                entity.setMetadata(OBJECT_MAPPER.writeValueAsString(meta));
            } catch (Exception ignored) {}
            forestNodeJpaRepository.save(entity);
        });
    }

    // =====================================================================
    // 读操作（SQL WHERE 过滤替代 JSONB 内存过滤）
    // =====================================================================

    @Override
    public Optional<MemoryEntry> findByIdAndAccount(UUID memoryId, UUID accountId) {
        return forestNodeJpaRepository.findById(memoryId.toString())
                .map(this::toMemoryEntry);
    }

    @Override
    public List<MemoryEntry> findByLevelAndScope(UUID accountId, MemoryLevel level, String scopeId) {
        UUID scopeUuid = parseScopeId(scopeId);
        if (scopeUuid == null) return List.of();
        return forestNodeJpaRepository.findMemoriesByLevelAndScope(accountId, level.name(), scopeUuid).stream()
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public List<MemoryEntry> findByLevelAndScopeAndProject(UUID accountId, MemoryLevel level, String scopeId, UUID projectId) {
        return findByLevelAndScope(accountId, level, scopeId).stream()
                .filter(e -> projectId == null || projectId.equals(e.getProjectId()))
                .toList();
    }

    @Override
    public List<MemoryEntry> findByLevel(UUID accountId, MemoryLevel level) {
        return forestNodeJpaRepository.findMemoriesByLevel(accountId, level.name()).stream()
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public List<MemoryEntry> findAllByAccount(UUID accountId) {
        return forestNodeJpaRepository.findMemoriesByAccount(accountId).stream()
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public List<MemoryEntry> findAllByAccountPage(UUID accountId, int page, int size) {
        return findAllByAccount(accountId);
    }

    @Override
    public List<MemoryEntry> findAllByAccountPage(UUID accountId, String level, int page, int size) {
        if (level != null && !level.isBlank()) {
            return findByLevel(accountId, MemoryLevel.valueOf(level));
        }
        return findAllByAccount(accountId);
    }

    @Override
    public List<MemoryEntry> findAllNonArchived() {
        return forestNodeJpaRepository.findMemoriesNonArchived().stream()
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public List<MemoryEntry> findImportant(UUID accountId, UUID projectId, int topN) {
        return forestNodeJpaRepository.findMemoriesImportantByAccount(accountId).stream()
                .limit(topN)
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public List<MemoryEntry> semanticSearch(UUID accountId, MemoryLevel level, String scopeId, float[] queryVec, int topK) {
        if (queryVec == null) return List.of();
        String vecStr = toVectorString(queryVec);
        if (level != null) {
            return forestNodeJpaRepository.semanticSearchMemoryByLevel(level.name(), vecStr, topK).stream()
                    .map(this::toMemoryEntry)
                    .filter(e -> e != null)
                    .toList();
        }
        return forestNodeJpaRepository.semanticSearchMemory("memory", vecStr, topK).stream()
                .map(this::toMemoryEntry)
                .filter(e -> e != null)
                .toList();
    }

    @Override
    public List<MemoryEntry> searchByKeyword(UUID accountId, String keyword, int page, int size) {
        return forestNodeJpaRepository.searchMemoriesByKeyword(accountId, keyword).stream()
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public long countByKeyword(UUID accountId, String keyword) {
        return forestNodeJpaRepository.countMemoriesByKeyword(accountId, keyword);
    }

    @Override
    public List<MemoryEntry> findArchivable(UUID accountId, float threshold) {
        return forestNodeJpaRepository.findMemoriesArchivable(accountId, BigDecimal.valueOf(threshold)).stream()
                .map(this::toMemoryEntry)
                .toList();
    }

    @Override
    public void delete(UUID memoryId) {
        forestNodeJpaRepository.deleteById(memoryId.toString());
    }

    @Override
    public void deleteBatch(List<UUID> ids) {
        ids.forEach(id -> forestNodeJpaRepository.deleteById(id.toString()));
    }

    @Override
    public long countByAccount(UUID accountId) {
        if (accountId == null) return forestNodeJpaRepository.countByNodeType("memory");
        return forestNodeJpaRepository.countMemoriesByAccount(accountId);
    }

    @Override
    public long countByAccount(UUID accountId, String level) {
        if (level == null || level.isBlank() || accountId == null) return countByAccount(accountId);
        return forestNodeJpaRepository.countMemoriesByAccountAndLevel(accountId, level);
    }

    @Override
    public long countImportantByAccount(UUID accountId) {
        return forestNodeJpaRepository.countMemoriesImportantByAccount(accountId);
    }

    @Override
    public long countArchivedByAccount(UUID accountId) {
        return forestNodeJpaRepository.countMemoriesArchivedByAccount(accountId);
    }

    // =====================================================================
    // 转换
    // =====================================================================

    /** ForestNodeEntity → MemoryEntry（从专用列读取，不从 JSONB 解析）。 */
    private MemoryEntry toMemoryEntry(ForestNodeEntity entity) {
        try {
            return MemoryEntry.builder()
                    .memoryId(UUID.fromString(entity.getNodeId()))
                    .accountId(entity.getAccountId())
                    .projectId(entity.getProjectId())
                    .level(MemoryLevel.valueOf(
                            entity.getLevel() != null ? entity.getLevel() : MemoryLevel.SESSION.name()))
                    .scopeId(entity.getScopeId() != null ? entity.getScopeId().toString() : null)
                    .content(entity.getContent())
                    .summary(entity.getSummary())
                    .archived(entity.getArchived())
                    .important(entity.getImportant())
                    .retention(entity.getRetention() != null ? entity.getRetention().floatValue() : null)
                    .accessCount(entity.getAccessCount() != null ? entity.getAccessCount() : 0)
                    .lastAccessedAt(entity.getLastAccessedAt() != null
                            ? entity.getLastAccessedAt().toLocalDateTime() : null)
                    .createdAt(entity.getCreatedAt() != null
                            ? entity.getCreatedAt().toLocalDateTime() : null)
                    .build();
        } catch (Exception e) {
            log.warn("转换记忆节点失败: nodeId={}", entity.getNodeId());
            return null;
        }
    }

    /** 将 float[] 向量转为 PostgreSQL 兼容的数组字符串。 */
    private static String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 安全解析 scopeId（String → UUID），无效返回 null。 */
    private static UUID parseScopeId(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) return null;
        try {
            return UUID.fromString(scopeId);
        } catch (Exception e) {
            return null;
        }
    }
}
