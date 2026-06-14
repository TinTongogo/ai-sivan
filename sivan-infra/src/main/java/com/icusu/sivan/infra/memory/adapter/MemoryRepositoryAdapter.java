package com.icusu.sivan.infra.memory.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.memory.IMemoryCrudRepository;
import com.icusu.sivan.domain.memory.IMemoryQueryRepository;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.infra.memory.entity.MemoryEntryEntity;
import com.icusu.sivan.infra.memory.repository.MemoryEntryJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆仓储适配器，实现 IMemoryRepository。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryRepositoryAdapter implements IMemoryRepository, IMemoryCrudRepository, IMemoryQueryRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setTimeZone(TimeZone.getTimeZone("UTC"));

    private final MemoryEntryJpaRepository jpaRepository;
    private final EmbeddingService embeddingService;

    /** 保存记忆条目，回写 ID 和时间戳。保存时自动计算并写入向量。 */
    @Override
    public UUID save(MemoryEntry entry) {
        MemoryEntryEntity entity = toEntity(entry);
        // 计算 content 的 embedding 向量（若 embedding 服务可用）
        if (entity.getVector() == null && entry.getContent() != null) {
            try {
                float[] vec = embeddingService.embed(entry.getContent());
                entity.setVector(vec);
            } catch (Exception e) {
                log.warn("记忆条目 embedding 计算失败: {}", e.getMessage());
            }
        }
        jpaRepository.save(entity);
        if (entry.getMemoryId() == null) {
            entry.setMemoryId(entity.getMemoryId());
        }
        entry.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        entry.setLastAccessedAt(entity.getLastAccessedAt() != null ? entity.getLastAccessedAt().toLocalDateTime() : null);
        return entity.getMemoryId();
    }

    /** 更新记忆条目（内容变更时自动重新计算向量）。 */
    @Override
    public void update(MemoryEntry entry) {
        // 找到现有记录并更新
        MemoryEntryEntity entity = jpaRepository.findById(entry.getMemoryId()).orElse(null);
        if (entity == null) {
            return;
        }
        boolean contentChanged = entry.getContent() != null && !entry.getContent().equals(entity.getContent());
        entity.setLevel(entry.getLevel() != null ? entry.getLevel().name() : entity.getLevel());
        entity.setScopeId(entry.getScopeId() != null ? entry.getScopeId() : entity.getScopeId());
        entity.setContent(entry.getContent() != null ? entry.getContent() : entity.getContent());
        entity.setMetadata(entry.getMetadata() != null ? toJsonString(entry.getMetadata()) : entity.getMetadata());
        entity.setRetention(entry.getRetention() != null ? entry.getRetention() : entity.getRetention());
        entity.setAccessCount(entry.getAccessCount() != null ? entry.getAccessCount() : entity.getAccessCount());
        entity.setArchived(entry.getArchived() != null ? entry.getArchived() : entity.getArchived());
        entity.setImportant(entry.getImportant() != null ? entry.getImportant() : entity.getImportant());
        entity.setSummary(entry.getSummary() != null ? entry.getSummary() : entity.getSummary());
        if (entry.getLastAccessedAt() != null) {
            entity.setLastAccessedAt(entry.getLastAccessedAt().atOffset(ZoneOffset.UTC));
        }
        // 内容变更时重新计算向量
        if (contentChanged) {
            try {
                float[] vec = embeddingService.embed(entity.getContent());
                entity.setVector(vec);
            } catch (Exception e) {
                log.warn("记忆条目 embedding 重计算失败: {}", e.getMessage());
            }
        }
        jpaRepository.save(entity);
    }

    /** 根据 ID 和账号查询记忆条目。 */
    @Override
    public Optional<MemoryEntry> findByIdAndAccount(UUID memoryId, UUID accountId) {
        return jpaRepository.findByMemoryIdAndAccountId(memoryId, accountId)
                .map(this::toDomain);
    }

    /** 根据记忆级别和作用域查询记忆条目。 */
    @Override
    public List<MemoryEntry> findByLevelAndScope(UUID accountId, MemoryLevel level, String scopeId) {
        return jpaRepository.findByAccountIdAndLevelAndScopeId(accountId, level.name(), scopeId)
                .stream().map(this::toDomain).toList();
    }

    /** 根据记忆级别、作用域和项目查询记忆条目。 */
    @Override
    public List<MemoryEntry> findByLevelAndScopeAndProject(UUID accountId, MemoryLevel level,
                                                            String scopeId, UUID projectId) {
        return jpaRepository.findByAccountIdAndLevelAndScopeId(accountId, level.name(), scopeId)
                .stream()
                .filter(e -> e.getProjectId() != null && e.getProjectId().equals(projectId))
                .map(this::toDomain).toList();
    }

    /** 查询账号下所有记忆条目。 */
    @Override
    public List<MemoryEntry> findAllByAccount(UUID accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(this::toDomain).toList();
    }

    /** 分页查询账号下所有记忆条目（按创建时间降序）。 */
    @Override
    public List<MemoryEntry> findAllByAccountPage(UUID accountId, int page, int size) {
        return jpaRepository.findByAccountId(accountId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(this::toDomain).toList();
    }

    /** 分页查询账号下记忆条目（按层级筛选，按创建时间降序）。 */
    @Override
    public List<MemoryEntry> findAllByAccountPage(UUID accountId, String level, int page, int size) {
        if (level != null && !level.isBlank()) {
            return jpaRepository.findByAccountIdAndLevel(accountId, level,
                            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                    .stream().map(this::toDomain).toList();
        }
        return findAllByAccountPage(accountId, page, size);
    }

    /** 按关键词分页搜索记忆条目（keyword 中的 % _ 已转义防通配符注入）。 */
    @Override
    public List<MemoryEntry> searchByKeyword(UUID accountId, String keyword, int page, int size) {
        String escaped = escapeLikePattern(keyword);
        return jpaRepository.searchByKeyword(accountId, escaped, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countByKeyword(UUID accountId, String keyword) {
        String escaped = escapeLikePattern(keyword);
        return jpaRepository.countByAccountIdAndContentContainingIgnoreCaseOrSummaryContainingIgnoreCase(accountId, escaped, escaped);
    }

    /** 转义 LIKE 模式中的通配符 % 和 _。 */
    private static String escapeLikePattern(String input) {
        if (input == null) return null;
        return input.replace("\\", "\\\\\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** 查询可归档的低 retention 记忆条目。 */
    @Override
    public List<MemoryEntry> findArchivable(UUID accountId, float threshold) {
        return jpaRepository.findByAccountIdAndArchivedFalseAndRetentionLessThan(accountId, threshold)
                .stream().map(this::toDomain).toList();
    }

    /** 查询所有未归档的记忆条目（跨账户，供调度任务使用）。 */
    @Override
    public List<MemoryEntry> findAllNonArchived() {
        return jpaRepository.findByArchivedFalse().stream()
                .map(this::toDomain).toList();
    }

    /** 查询重要的记忆条目。 */
    @Override
    public List<MemoryEntry> findImportant(UUID accountId, UUID projectId, int limit) {
        return jpaRepository.findByAccountIdAndImportantTrueAndProjectId(accountId, projectId)
                .stream().limit(limit).map(this::toDomain).toList();
    }

    /** 查询指定层级且未归档的记忆条目（用于长期记忆注入）。 */
    @Override
    public List<MemoryEntry> findByLevel(UUID accountId, MemoryLevel level) {
        return jpaRepository.findByAccountIdAndLevelAndArchivedFalse(accountId, level.name())
                .stream().map(this::toDomain).toList();
    }

    /** 语义搜索记忆条目（余弦相似度 > 0.5 阈值过滤）。 */
    @Override
    public List<MemoryEntry> semanticSearch(UUID accountId, MemoryLevel level, String scopeId,
                                             float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0) {
            log.warn("语义搜索跳过：查询向量为空");
            return Collections.emptyList();
        }
        String vecStr = Arrays.toString(queryVector);
        List<MemoryEntryEntity> entities = jpaRepository.semanticSearch(
                accountId, level.name(), vecStr, topK);
        return entities.stream()
                .map(this::toDomain)
                .toList();
    }

    /** 根据 ID 删除记忆条目。 */
    @Override
    public void delete(UUID memoryId) {
        jpaRepository.deleteById(memoryId);
    }

    @Override
    public void deleteBatch(java.util.List<UUID> ids) {
        if (ids != null && !ids.isEmpty()) jpaRepository.deleteAllById(ids);
    }

    /** 统计账号下记忆条目总数。 */
    @Override
    public long countByAccount(UUID accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    /** 统计账号下记忆条目总数（按层级筛选）。 */
    @Override
    public long countByAccount(UUID accountId, String level) {
        if (level != null && !level.isBlank()) {
            return jpaRepository.countByAccountIdAndLevel(accountId, level);
        }
        return countByAccount(accountId);
    }

    /** 统计账号下的重要记忆条目数。 */
    @Override
    public long countImportantByAccount(UUID accountId) {
        return jpaRepository.countByAccountIdAndImportantTrue(accountId);
    }

    /** 统计账号下的已归档记忆条目数。 */
    @Override
    public long countArchivedByAccount(UUID accountId) {
        return jpaRepository.countByAccountIdAndArchivedTrue(accountId);
    }

    // ---- 转换方法 ----

    /** 将实体转换为领域对象。 */
    private MemoryEntry toDomain(MemoryEntryEntity entity) {
        return MemoryEntry.builder()
                .memoryId(entity.getMemoryId())
                .accountId(entity.getAccountId())
                .projectId(entity.getProjectId())
                .level(entity.getLevel() != null ? MemoryLevel.valueOf(entity.getLevel()) : null)
                .scopeId(entity.getScopeId())
                .content(entity.getContent())
                .metadata(parseJsonMap(entity.getMetadata()))
                .retention(entity.getRetention())
                .accessCount(entity.getAccessCount())
                .archived(entity.getArchived())
                .important(entity.getImportant())
                .summary(entity.getSummary())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .lastAccessedAt(entity.getLastAccessedAt() != null ? entity.getLastAccessedAt().toLocalDateTime() : null)
                .build();
    }

    /** 将领域对象转换为实体。 */
    private MemoryEntryEntity toEntity(MemoryEntry entry) {
        MemoryEntryEntity entity = new MemoryEntryEntity();
        entity.setMemoryId(entry.getMemoryId());
        entity.setAccountId(entry.getAccountId());
        entity.setProjectId(entry.getProjectId());
        entity.setLevel(entry.getLevel() != null ? entry.getLevel().name() : null);
        entity.setScopeId(entry.getScopeId());
        entity.setContent(entry.getContent());
        entity.setMetadata(toJsonString(entry.getMetadata()));
        entity.setRetention(entry.getRetention() != null ? entry.getRetention() : 1.0f);
        entity.setAccessCount(entry.getAccessCount() != null ? entry.getAccessCount() : 0);
        entity.setArchived(entry.getArchived() != null ? entry.getArchived() : false);
        entity.setImportant(entry.getImportant() != null ? entry.getImportant() : false);
        entity.setSummary(entry.getSummary());
        entity.setVector(entry.getVector());
        entity.setLastAccessedAt(entry.getLastAccessedAt() != null
                ? entry.getLastAccessedAt().atOffset(ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC));
        return entity;
    }

    /** 将对象序列化为 JSON 字符串。 */
    private String toJsonString(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return "{}";
        }
    }

    /** 将 JSON 字符串解析为 Map。 */
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { log.warn("JSON 序列化/反序列化失败", e);
            return Collections.emptyMap();
        }
    }
}
