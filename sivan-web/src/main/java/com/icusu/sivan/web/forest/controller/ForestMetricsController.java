package com.icusu.sivan.web.forest.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.infra.forest.sink.ForestMetricsCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/forest/metrics")
@RequiredArgsConstructor
public class ForestMetricsController {

    private final ForestMetricsCollector metricsCollector;

    @GetMapping
    public BaseResponse<Map<String, Object>> getMetrics() {
        return BaseResponse.success(metricsCollector.snapshot());
    }
}
