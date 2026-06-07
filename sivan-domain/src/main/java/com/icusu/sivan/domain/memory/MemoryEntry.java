package com.icusu.sivan.domain.memory;

import com.icusu.sivan.common.enums.MemoryLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆条目实体，对应四层认知模型：Session / User / Team / Project。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    private UUID memoryId;
    private UUID accountId;
    private UUID projectId;
    private MemoryLevel level;
    private String scopeId;
    private String content;
    private Map<String, Object> metadata;
    private Float retention;
    private Integer accessCount;
    private Boolean archived;
    private Boolean important;
    private String summary;
    /** 预计算向量（多模态），持久化时写入；null 时由适配器从 content 自动计算 text-only 向量 */
    private float[] vector;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;

    public void archive() { this.archived = true; }
    public void access() { this.accessCount = (this.accessCount != null ? this.accessCount : 0) + 1; this.lastAccessedAt = LocalDateTime.now(); }
    public void decay(float factor) { this.retention = Math.max(0.0f, (this.retention != null ? this.retention : 1.0f) * factor); }
    public void markImportant() { this.important = true; }
    public boolean isArchived() { return Boolean.TRUE.equals(this.archived); }
    public void updateFrom(String content, String summary, Boolean important, Float retention, Boolean archived) {
        if (content != null) this.content = content;
        if (summary != null) this.summary = summary;
        if (important != null) this.important = important;
        if (retention != null) this.retention = retention;
        if (archived != null) this.archived = archived;
    }
}
