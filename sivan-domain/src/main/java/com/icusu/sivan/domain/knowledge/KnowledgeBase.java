package com.icusu.sivan.domain.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库实体（10-知识库与RAG §6.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    private String kbName;
    private UUID accountId;
    private UUID projectId;
    private String description;
    /** 检索策略配置，null 表示使用默认值。 */
    private RetrievalConfig retrievalConfig;
    /** 索引状态。 */
    @Builder.Default
    private IndexStatus indexStatus = IndexStatus.COMPLETE;
    /** 最后索引时间。 */
    private LocalDateTime lastIndexedAt;
    /** 文档数（定期刷新）。 */
    private int documentCount;
    /** 分块数（定期刷新）。 */
    private int chunkCount;
    @Builder.Default
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void updateDescription(String description) {
        this.description = description;
        this.updatedAt = LocalDateTime.now();
    }

    /** 获取检索配置，null 时返回默认值。 */
    public RetrievalConfig effectiveRetrievalConfig() {
        return retrievalConfig != null ? retrievalConfig : RetrievalConfig.defaults();
    }
}
