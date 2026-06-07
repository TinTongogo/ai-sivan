package com.icusu.sivan.domain.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库实体。
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void updateDescription(String description) { this.description = description; this.updatedAt = LocalDateTime.now(); }
}
