package com.icusu.sivan.domain.shared.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * 文档分块值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    private String chunkId;
    private String kbName;
    private UUID docId;
    private String text;
    private String contentType;
    private String imagePath;
    private Map<String, Object> metadata;
    /** 内容的 SHA-256 哈希（前 32 字符），用于增量索引对比 */
    private String contentHash;

    /** 计算文本内容的 SHA-256 哈希（前 32 字符）。 */
    public static String computeHash(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
