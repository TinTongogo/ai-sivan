package com.icusu.sivan.web.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 搜索结果响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class SearchResultResponse {
    private String chunkId;
    private String kbName;
    private String text;
    private String contentType;
    private String imagePath;
    private double score;
    private Map<String, Object> metadata;
}
