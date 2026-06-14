package com.icusu.sivan.web.token.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.application.token.TokenUsageService;
import com.icusu.sivan.application.token.dto.AgentTokenSummary;
import com.icusu.sivan.application.token.dto.DailyConsumptionResponse;
import com.icusu.sivan.application.token.dto.TokenUsageSummaryResponse;
import com.icusu.sivan.application.model.dto.ModelTokenSummary;
import com.icusu.sivan.application.token.dto.TrendPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * Token 用量统计控制器。
 */
@RestController
@RequestMapping("/api/token-usage")
@RequiredArgsConstructor
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    /** 获取 Token 用量汇总。 */
    @GetMapping("/summary")
    public BaseResponse<TokenUsageSummaryResponse> getSummary(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(tokenUsageService.getSummary(accountId));
    }

    /** 获取 Token 每日趋势。 */
    @GetMapping("/daily-trend")
    public BaseResponse<List<TrendPoint>> getDailyTrend(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(tokenUsageService.getDailyTrend(accountId));
    }

    /** 按智能体统计 Token 用量。 */
    @GetMapping("/by-agent")
    public BaseResponse<List<AgentTokenSummary>> getByAgent(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(tokenUsageService.getByAgent(accountId));
    }

    /** 按模型统计 Token 用量。 */
    @GetMapping("/by-model")
    public BaseResponse<List<ModelTokenSummary>> getByModel(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(tokenUsageService.getByModel(accountId));
    }

    /** 获取每日 Token 消耗概览（贡献度图）。 */
    @GetMapping("/daily-consumption")
    public BaseResponse<List<DailyConsumptionResponse>> getDailyConsumption(
            @RequestParam(defaultValue = "90") int days, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(tokenUsageService.getDailyConsumption(accountId, days));
    }
}
