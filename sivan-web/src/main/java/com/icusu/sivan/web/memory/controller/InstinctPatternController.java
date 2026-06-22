package com.icusu.sivan.web.memory.controller;

import com.icusu.sivan.application.memory.SharedTemplateAppService;
import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.SharedTemplate;
import com.icusu.sivan.application.memory.dto.PatternResponse;
import com.icusu.sivan.application.memory.dto.ShareRequest;
import com.icusu.sivan.application.memory.dto.SharedTemplateResponse;
import com.icusu.sivan.web.shared.security.CurrentAccountId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 本能模板控制器。管理本能模板的查询、删除与共享。
 */
@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
public class InstinctPatternController {

    private final IInstinctPatternRepository patternRepository;
    private final ISharedTemplateRepository sharedTemplateRepository;
    private final SharedTemplateAppService sharedTemplateAppService;

    // ===== 本能模板 CRUD =====

    /** 获取当前账户的本能模板列表，按创建时间降序。 */
    @GetMapping
    public BaseResponse<List<PatternResponse>> list(@CurrentAccountId UUID accountId) {
        List<InstinctPattern> patterns = new ArrayList<>(patternRepository.findActiveByAccount(accountId));
        patterns.sort(Comparator.comparing(InstinctPattern::getCreatedAt).reversed());
        return BaseResponse.success(patterns.stream().map(this::toPatternResponse).toList());
    }

    /** 获取模板详情。 */
    @GetMapping("/{patternId}")
    public BaseResponse<PatternResponse> getById(@PathVariable UUID patternId, @CurrentAccountId UUID accountId) {
        InstinctPattern pattern = patternRepository.findById(patternId)
                .orElseThrow(() -> new IllegalArgumentException("本能模板不存在: " + patternId));
        return BaseResponse.success(toPatternResponse(pattern));
    }

    /** 删除模板。 */
    @DeleteMapping("/{patternId}")
    public BaseResponse<Void> delete(@PathVariable UUID patternId, @CurrentAccountId UUID accountId) {
        InstinctPattern pattern = patternRepository.findById(patternId)
                .orElseThrow(() -> new IllegalArgumentException("本能模板不存在: " + patternId));
        if (!accountId.equals(pattern.getAccountId())) {
            throw new IllegalArgumentException("只能删除自己的模板");
        }
        patternRepository.delete(patternId);
        return BaseResponse.success();
    }

    // ===== 分享 =====

    /** 分享本能模板。 */
    @PostMapping("/{patternId}/share")
    public BaseResponse<SharedTemplateResponse> share(@PathVariable UUID patternId,
                                                       @RequestBody ShareRequest request,
                                                       @CurrentAccountId UUID accountId) {
        SharedTemplate.Visibility visibility = SharedTemplate.Visibility.valueOf(request.getVisibility());
        SharedTemplate template = sharedTemplateAppService.share(patternId, accountId, visibility);
        return BaseResponse.success(toSharedResponse(template));
    }

    /** 取消分享。 */
    @DeleteMapping("/share/{templateId}")
    public BaseResponse<Void> unshare(@PathVariable UUID templateId, @CurrentAccountId UUID accountId) {
        sharedTemplateAppService.unshare(templateId, accountId);
        return BaseResponse.success();
    }

    // ===== 共享模板查询 =====

    /** 我分享的模板列表。 */
    @GetMapping("/shared/mine")
    public BaseResponse<List<SharedTemplateResponse>> myShared(@CurrentAccountId UUID accountId) {
        List<SharedTemplate> list = sharedTemplateRepository.findByOwner(accountId);
        return BaseResponse.success(list.stream().map(this::toSharedResponse).toList());
    }

    /** 我可访问的他人共享模板列表。 */
    @GetMapping("/shared/accessible")
    public BaseResponse<List<SharedTemplateResponse>> accessible(@CurrentAccountId UUID accountId) {
        List<SharedTemplate> list = sharedTemplateAppService.findAccessibleTemplates(accountId);
        return BaseResponse.success(list.stream().map(this::toSharedResponse).toList());
    }

    // ===== 转换 =====

    private PatternResponse toPatternResponse(InstinctPattern p) {
        return PatternResponse.builder()
                .patternId(p.getPatternId())
                .accountId(p.getAccountId())
                .executionMode(p.getExecutionMode())
                .hitCount(p.getHitCount())
                .successCount(p.getSuccessCount())
                .totalCount(p.getTotalCount())
                .sourcePatternId(p.getSourcePatternId())
                .version(p.getVersion())
                .active(p.getActive())
                .modeSuccessRate(p.getModeSuccessRate())
                .lastMatchAt(p.getLastMatchAt())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private SharedTemplateResponse toSharedResponse(SharedTemplate t) {
        SharedTemplateResponse.SharedTemplateResponseBuilder builder = SharedTemplateResponse.builder()
                .templateId(t.getTemplateId())
                .patternId(t.getPatternId())
                .ownerAccountId(t.getOwnerAccountId())
                .visibility(t.getVisibility().name())
                .status(t.getStatus())
                .quality(t.getQuality())
                .useCount(t.getUseCount())
                .successCount(t.getSuccessCount())
                .sharedAt(t.getSharedAt())
                .createdAt(t.getCreatedAt());

        // 附上模板基本信息
        if (t.getPatternId() != null) {
            patternRepository.findById(t.getPatternId()).ifPresent(p -> {
                builder.executionMode(p.getExecutionMode());
                builder.patternVersion(p.getVersion());
            });
        }

        return builder.build();
    }
}
