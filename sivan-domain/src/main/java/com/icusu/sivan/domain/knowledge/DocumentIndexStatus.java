package com.icusu.sivan.domain.knowledge;

/**
 * 文档索引状态枚举（10-知识库与RAG §6.3）。
 */
public enum DocumentIndexStatus {
    PENDING,
    INDEXED,
    FAILED,
    SKIPPED;

    /** 兼容旧版字符串状态（PARSING/READY/FAILED）。 */
    public static DocumentIndexStatus fromLegacyString(String legacy) {
        if (legacy == null) return PENDING;
        return switch (legacy.toUpperCase()) {
            case "PARSING", "INDEXING" -> PENDING;
            case "READY", "INDEXED" -> INDEXED;
            case "FAILED" -> FAILED;
            case "SKIPPED" -> SKIPPED;
            default -> PENDING;
        };
    }
}
