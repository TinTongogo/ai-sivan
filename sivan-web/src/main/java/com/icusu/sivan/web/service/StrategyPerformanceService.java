package com.icusu.sivan.web.routing.service;

import com.icusu.sivan.domain.routing.IStrategyPerformanceRepository;
import com.icusu.sivan.domain.routing.StrategyPerformance;
import com.icusu.sivan.web.routing.dto.StrategyPerformanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 策略性能统计服务：记录路由策略执行效果，支持查询与闭环反馈。 */
@Service
@RequiredArgsConstructor
public class StrategyPerformanceService {

    private final IStrategyPerformanceRepository repository;

    /** 记录策略被使用（total +1，累计置信度）。 */
    public void recordUsage(UUID accountId, String strategy, double confidence) {
        repository.findByAccountAndStrategy(accountId, strategy)
                .ifPresentOrElse(
                        sp -> {
                            sp.setTotal(sp.getTotal() + 1);
                            sp.setSumConfidence(sp.getSumConfidence() + confidence);
                            repository.update(sp);
                        },
                        () -> {
                            var sp = StrategyPerformance.builder()
                                    .accountId(accountId)
                                    .strategy(strategy)
                                    .total(1)
                                    .success(0)
                                    .sumConfidence(confidence)
                                    .build();
                            repository.save(sp);
                        });
    }

    /** 记录策略执行成功（success +1），供对话完成后的闭环反馈调用。 */
    public void recordSuccess(UUID accountId, String strategy) {
        repository.findByAccountAndStrategy(accountId, strategy)
                .ifPresent(sp -> {
                    sp.setSuccess(sp.getSuccess() + 1);
                    sp.setTotal(Math.max(sp.getTotal(), sp.getSuccess())); // 防止 success > total
                    repository.update(sp);
                });
    }

    /** 重置当前用户的策略性能统计数据。 */
    public void reset(UUID accountId) {
        repository.deleteByAccount(accountId);
    }

    /** 查询当前用户的所有策略性能统计数据。 */
    public List<StrategyPerformanceResponse> listCurrent(UUID accountId) {
        return repository.findAllByAccount(accountId).stream()
                .map(this::toResponse)
                .toList();
    }

    private StrategyPerformanceResponse toResponse(StrategyPerformance sp) {
        return StrategyPerformanceResponse.builder()
                .id(sp.getId())
                .strategy(sp.getStrategy())
                .total(sp.getTotal())
                .success(sp.getSuccess())
                .avgConfidence(sp.getTotal() > 0 ? sp.getSumConfidence() / sp.getTotal() : 0)
                .successRate(sp.getTotal() > 0 ? (double) sp.getSuccess() / sp.getTotal() : 0)
                .updatedAt(sp.getUpdatedAt())
                .build();
    }
}
