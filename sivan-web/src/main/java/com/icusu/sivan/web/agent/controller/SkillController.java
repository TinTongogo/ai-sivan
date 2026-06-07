package com.icusu.sivan.web.agent.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.web.agent.dto.CreateSkillRequest;
import com.icusu.sivan.web.agent.dto.UpdateSkillRequest;
import com.icusu.sivan.web.agent.dto.SkillResponse;
import com.icusu.sivan.web.agent.service.SkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * 技能管理控制器。
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /** 创建技能。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<SkillResponse> create(@Valid @RequestBody CreateSkillRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.created(skillService.create(accountId, request));
    }

    /** 根据 ID 获取技能。 */
    @GetMapping("/{skillId}")
    public BaseResponse<SkillResponse> getById(@PathVariable UUID skillId, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(skillService.getById(accountId, skillId));
    }

    /** 获取技能列表，可按分类、来源过滤。 */
    @GetMapping
    public BaseResponse<List<SkillResponse>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String skillType, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(skillService.list(accountId, category, skillType));
    }

    /** 更新技能信息。 */
    @PutMapping("/{skillId}")
    public BaseResponse<SkillResponse> update(@PathVariable UUID skillId,
                                               @Valid @RequestBody UpdateSkillRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(skillService.update(accountId, skillId, request));
    }

    /** 删除技能。 */
    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID skillId, @CurrentAccountId UUID accountId) {
        skillService.delete(accountId, skillId);
        return BaseResponse.success();
    }

    /** 批量删除技能。 */
    @PostMapping("/batch-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteBatch(@RequestBody java.util.List<UUID> skillIds,
                                           @CurrentAccountId UUID accountId) {
        skillService.deleteBatch(skillIds, accountId);
        return BaseResponse.success();
    }
}
