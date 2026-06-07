package com.icusu.sivan.domain.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 画像变更日志实体。
 * 记录 UserProfile 字段的每次变更，支持审计和回溯。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileChangeLog {

    private UUID logId;
    private UUID accountId;
    /** 变更来源：manual / auto_learn */
    private String source;
    /** 变更的字段名 */
    private String fieldName;
    /** 变更前的值（截断至 500 字符） */
    private String oldValue;
    /** 变更后的值（截断至 500 字符） */
    private String newValue;
    private LocalDateTime createdAt;

    /** 字段值截断工厂方法 */
    public static ProfileChangeLog of(UUID accountId, String source, String fieldName,
                                       String oldValue, String newValue) {
        return ProfileChangeLog.builder()
                .logId(UUID.randomUUID())
                .accountId(accountId)
                .source(source)
                .fieldName(fieldName)
                .oldValue(truncate(oldValue, 500))
                .newValue(truncate(newValue, 500))
                .build();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
