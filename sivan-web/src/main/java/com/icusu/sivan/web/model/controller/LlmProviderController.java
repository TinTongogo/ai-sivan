package com.icusu.sivan.web.model.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.model.dto.*;
import com.icusu.sivan.web.service.LlmProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * LLM 提供商配置管理控制器。
 */
@RestController
@RequestMapping("/api/llm-providers")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderService llmProviderService;

    /**
     * 创建 LLM 提供商配置。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<LlmProviderResponse> create(@Valid @RequestBody CreateLlmProviderRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(llmProviderService.create(accountId, request));
    }

    /**
     * 根据 ID 获取 LLM 提供商。
     */
    @GetMapping("/{providerId}")
    public BaseResponse<LlmProviderResponse> getById(@PathVariable UUID providerId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(llmProviderService.getById(accountId, providerId));
    }

    /**
     * 获取 LLM 提供商列表。
     */
    @GetMapping
    public BaseResponse<List<LlmProviderResponse>> list(@CurrentAccountId UUID accountId) {
                return BaseResponse.success(llmProviderService.list(accountId));
    }

    /**
     * 更新 LLM 提供商配置。
     */
    @PutMapping("/{providerId}")
    public BaseResponse<LlmProviderResponse> update(@PathVariable UUID providerId, @Valid @RequestBody UpdateLlmProviderRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(llmProviderService.update(accountId, providerId, request));
    }

    /**
     * 删除 LLM 提供商配置。
     */
    @DeleteMapping("/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID providerId, @CurrentAccountId UUID accountId) {
                llmProviderService.delete(accountId, providerId);
        return BaseResponse.success();
    }

    /**
     * 将指定提供商设为默认。
     */
    @PostMapping("/{providerId}/set-default")
    public BaseResponse<LlmProviderResponse> setDefault(@PathVariable UUID providerId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(llmProviderService.setDefault(accountId, providerId, true));
    }

    /**
     * 获取所有可用的模型能力列表。
     */
    @GetMapping("/capabilities")
    public BaseResponse<List<ModelCapabilityInfo>> listCapabilities() {
        return BaseResponse.success(llmProviderService.getAllCapabilities());
    }

    /**
     * 获取指定 providerType 的默认能力集。
     */
    @GetMapping("/capabilities/{providerType}")
    public BaseResponse<List<String>> getDefaultCapabilities(@PathVariable String providerType,
                                                              @RequestParam(required = false) String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            return BaseResponse.success(llmProviderService.inferCapabilities(modelName, providerType));
        }
        return BaseResponse.success(llmProviderService.getDefaultCapabilities(providerType));
    }

    /**
     * 测试 LLM 连接。
     */
    @PostMapping("/test")
    public BaseResponse<LlmTestResult> testConnection(@Valid @RequestBody LlmConnectionRequest request) {
        LlmTestResult result = llmProviderService.testConnection(
                request.getProviderType(), request.getApiKey(), request.getBaseUrl());
        return BaseResponse.success(result);
    }

    /**
     * 获取 LLM 模型列表。
     */
    @PostMapping("/models")
    public BaseResponse<LlmModelListResult> fetchModels(@Valid @RequestBody LlmConnectionRequest request) {
        LlmModelListResult result = llmProviderService.fetchModels(
                request.getProviderType(), request.getApiKey(), request.getBaseUrl());
        return BaseResponse.success(result);
    }
}
