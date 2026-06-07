package com.icusu.sivan.web.token.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 趋势数据点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendPoint {
    private int bucket;
    private long totalInput;
    private long totalOutput;
}
