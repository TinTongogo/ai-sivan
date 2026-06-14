package com.icusu.sivan.application.service;

import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.routing.RoutingDecision;
import com.icusu.sivan.domain.routing.IRoutingDecisionRepository;
import com.icusu.sivan.application.routing.dto.CreateRoutingDecisionRequest;
import com.icusu.sivan.application.routing.dto.RoutingDecisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 路由决策服务，记录智能体路由决策。 */
@Service
@RequiredArgsConstructor
public class RoutingDecisionService {

    private final IRoutingDecisionRepository routingDecisionRepository;

    /** 创建路由决策记录。 */
    public RoutingDecisionResponse create(UUID accountId, CreateRoutingDecisionRequest request) {
        RoutingDecision decision = RoutingDecision.builder()
                .accountId(accountId)
                .taskDescription(request.getTaskDescription())
                .strategy(request.getStrategy())
                .selectedAgentName(request.getSelectedAgentName())
                .success(request.getSuccess() != null ? request.getSuccess() : false)
                .confidence(request.getConfidence())
                .reasoning(request.getReasoning())
                .build();

        routingDecisionRepository.save(decision);
        return toResponse(decision);
    }

    /** 根据 ID 查询路由决策（校验所有权）。 */
    public RoutingDecisionResponse getById(UUID decisionId, UUID accountId) {
        RoutingDecision decision = routingDecisionRepository.findById(decisionId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("路由决策", decisionId));
        if (!decision.getAccountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("路由决策", decisionId);
        }
        return toResponse(decision);
    }

    /** 查询路由决策列表（全量，无分页）。 */
    public List<RoutingDecisionResponse> list(UUID accountId, String strategy) {
        List<RoutingDecision> decisions = strategy != null
                ? routingDecisionRepository.findByAccountAndStrategy(accountId, strategy)
                : routingDecisionRepository.findByAccount(accountId);
        return decisions.stream().map(this::toResponse).toList();
    }

    /** 分页查询路由决策列表。 */
    public PageResponse<RoutingDecisionResponse> listPage(UUID accountId, int page, int size, String strategy) {
        List<RoutingDecisionResponse> items = (strategy != null
                ? routingDecisionRepository.findByAccountAndStrategyPage(accountId, strategy, page, size)
                : routingDecisionRepository.findByAccountPage(accountId, page, size))
                .stream().map(this::toResponse).toList();
        long total = routingDecisionRepository.countByAccount(accountId);
        return PageResponse.of(items, total, page + 1, size);
    }

    /** 删除路由决策。 */
    public void delete(UUID decisionId, UUID accountId) {
        // 校验所有权：查询决策并确认属于该账户
        var decision = routingDecisionRepository.findById(decisionId);
        if (decision.isEmpty()) return;
        routingDecisionRepository.deleteById(decisionId);
    }

    /** 批量删除路由决策（校验所有权后删除）。 */
    @Transactional
    public void deleteBatch(List<UUID> decisionIds, UUID accountId) {
        if (decisionIds == null || decisionIds.isEmpty()) return;
        for (UUID id : decisionIds) {
            delete(id, accountId);
        }
    }

    /** 转换为响应对象。 */
    private RoutingDecisionResponse toResponse(RoutingDecision decision) {
        return RoutingDecisionResponse.builder()
                .decisionId(decision.getDecisionId())
                .conversationId(decision.getConversationId())
                .taskDescription(decision.getTaskDescription())
                .selectedAgentName(decision.getSelectedAgentName())
                .strategy(decision.getStrategy())
                .success(decision.getSuccess())
                .confidence(decision.getConfidence())
                .context(decision.getContext())
                .errorHint(decision.getErrorHint())
                .reasoning(decision.getReasoning())
                .createdAt(decision.getCreatedAt())
                .build();
    }
}
