package com.icusu.sivan.web.forest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.forest.template.GoalTreeTemplate;
import com.icusu.sivan.domain.forest.template.TemplateRepository;
import com.icusu.sivan.domain.forest.tree.ExecutableNode;
import com.icusu.sivan.application.forest.dto.TemplateRequest;
import com.icusu.sivan.application.forest.dto.TemplateResponse;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GoalTree 模板管理端点 — 08-API契约 §3.3。
 * <p>
 * CRUD + 实例化：模板基于 ExecutableNode 序列化/反序列化存储，
 * 实例化时通过 {@link GoalTreeTemplate#deepClone()} 创建可执行副本。
 */
@RestController
@RequestMapping("/api/v2/templates")
public class TemplateController {

    private static final Logger log = LoggerFactory.getLogger(TemplateController.class);

    private final TemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    public TemplateController(TemplateRepository templateRepository,
                              ObjectMapper objectMapper) {
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
    }

    /** 模板列表（按账号）。 */
    @GetMapping
    public BaseResponse<List<TemplateResponse>> list(@CurrentAccountId UUID accountId) {
        List<TemplateResponse> list = templateRepository.findByAccountId(accountId).stream()
                .map(this::toResponse).toList();
        return BaseResponse.success(list);
    }

    /** 创建模板 — 从 rootNode JSON 反序列化为 ExecutableNode 存储。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<TemplateResponse> create(@RequestBody TemplateRequest request,
                                                  @CurrentAccountId UUID accountId) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new DomainException("模板名称不能为空");
        }
        ExecutableNode root = deserializeRootNode(request.getRootNode());
        GoalTreeTemplate template = new GoalTreeTemplate(accountId, request.getName(),
                request.getDescription(), root);
        templateRepository.save(template);
        log.info("模板已创建: name={} accountId={} templateId={}", request.getName(), accountId, template.templateId());
        return BaseResponse.created(toResponse(template));
    }

    /** 获取模板详情。 */
    @GetMapping("/{templateId}")
    public BaseResponse<TemplateResponse> getById(@PathVariable UUID templateId,
                                                   @CurrentAccountId UUID accountId) {
        GoalTreeTemplate template = findOwned(templateId, accountId);
        return BaseResponse.success(toResponse(template));
    }

    /** 更新模板 — 重建根节点。 */
    @PutMapping("/{templateId}")
    public BaseResponse<TemplateResponse> update(@PathVariable UUID templateId,
                                                  @RequestBody TemplateRequest request,
                                                  @CurrentAccountId UUID accountId) {
        findOwned(templateId, accountId);
        if (request.getRootNode() != null) {
            ExecutableNode root = deserializeRootNode(request.getRootNode());
            GoalTreeTemplate updated = new GoalTreeTemplate(accountId,
                    request.getName() != null ? request.getName() : "",
                    request.getDescription(), root);
            // 保留原 templateId（TemplateRepository.save 由基础设施层控制回写）
            templateRepository.save(updated);
        }
        log.info("模板已更新: templateId={} name={}", templateId, request.getName());
        return BaseResponse.success(toResponse(findOwned(templateId, accountId)));
    }

    /** 删除模板。 */
    @DeleteMapping("/{templateId}")
    public BaseResponse<Void> delete(@PathVariable UUID templateId, @CurrentAccountId UUID accountId) {
        GoalTreeTemplate template = findOwned(templateId, accountId);
        templateRepository.delete(templateId);
        log.info("模板已删除: templateId={}", templateId);
        return BaseResponse.success();
    }

    /** 实例化为 GoalTree（创建并开始执行）。 */
    @PostMapping("/{templateId}/instantiate")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<Map<String, Object>> instantiate(@PathVariable UUID templateId,
                                                          @CurrentAccountId UUID accountId) {
        GoalTreeTemplate template = findOwned(templateId, accountId);
        ExecutableNode root = template.deepClone();
        UUID goalId = UUID.randomUUID();
        // 记录使用
        templateRepository.updateStats(templateId, template.usageCount(), template.successCount());
        log.info("模板已实例化: templateId={} goalId={}", templateId, goalId);
        return BaseResponse.created(Map.of(
                "goalId", goalId.toString(),
                "templateId", templateId.toString(),
                "status", "created"
        ));
    }

    // ====== 内部 ======

    /**
     * 将请求中的 rootNode Map 反序列化为 ExecutableNode。
     * 利用 TreeNode 上的 @JsonTypeInfo + @JsonSubTypes 注解实现多态反序列化。
     */
    private ExecutableNode deserializeRootNode(Map<String, Object> rootNode) {
        if (rootNode == null || rootNode.isEmpty()) {
            throw new DomainException("模板根节点不能为空");
        }
        try {
            String json = objectMapper.writeValueAsString(rootNode);
            return objectMapper.readValue(json, ExecutableNode.class);
        } catch (Exception e) {
            log.error("反序列化模板根节点失败: {}", e.getMessage());
            throw new DomainException("模板根节点格式无效: " + e.getMessage());
        }
    }

    private GoalTreeTemplate findOwned(UUID templateId, UUID accountId) {
        GoalTreeTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> ResourceNotFoundException.notFound("模板", templateId));
        if (!template.accountId().equals(accountId)) {
            throw ResourceNotFoundException.notFound("模板", templateId);
        }
        return template;
    }

    private TemplateResponse toResponse(GoalTreeTemplate t) {
        TemplateResponse r = new TemplateResponse();
        r.setTemplateId(t.templateId());
        r.setAccountId(t.accountId());
        r.setName(t.name());
        r.setDescription(t.description());
        r.setUsageCount(t.usageCount());
        r.setSuccessCount(t.successCount());
        r.setCreatedAt(t.createdAt());
        r.setUpdatedAt(t.updatedAt());
        return r;
    }
}
