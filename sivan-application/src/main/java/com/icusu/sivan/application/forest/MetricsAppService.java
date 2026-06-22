package com.icusu.sivan.application.forest;

import com.icusu.sivan.infra.forest.sink.ForestMetricsCollector;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Forest 指标应用服务 — 包装 ForestMetricsCollector。
 */
@Service
public class MetricsAppService {

    private final ForestMetricsCollector metricsCollector;

    public MetricsAppService(ForestMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * 获取当前指标快照。
     */
    public Map<String, Object> snapshot() {
        return metricsCollector.snapshot();
    }
}
