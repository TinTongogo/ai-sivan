package com.icusu.sivan.web.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 搜索请求 DTO。
 */
@Data
public class SearchRequest {
    @NotBlank(message = "搜索文本不能为空")
    private String query;

    private int topK = 10;

    /** 搜索模式：VECTOR（向量搜索）、FULLTEXT（全文搜索） */
    private SearchMode mode = SearchMode.VECTOR;

    /** 是否启用查询改写扩展（VECTOR 模式下生效） */
    private boolean expandQuery = true;

    public enum SearchMode {
        VECTOR, FULLTEXT
    }
}
