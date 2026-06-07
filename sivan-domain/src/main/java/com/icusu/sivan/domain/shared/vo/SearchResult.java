package com.icusu.sivan.domain.shared.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量搜索结果值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    private String chunkId;
    private String kbName;
    private String text;
    private String contentType;
    private String imagePath;
    private double score;
    private Map<String, Object> metadata;
}
