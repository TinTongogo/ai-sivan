package com.icusu.sivan.web.memory.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.dto.PageResponse;
import com.icusu.sivan.web.memory.dto.CreateMemoryRequest;
import com.icusu.sivan.web.memory.dto.UpdateMemoryRequest;
import com.icusu.sivan.web.memory.dto.MemoryResponse;
import com.icusu.sivan.web.service.MemoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.icusu.sivan.web.shared.security.CurrentAccountId;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理控制器。
 */
@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    /** 创建记忆条目。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<MemoryResponse> create(@Valid @RequestBody CreateMemoryRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.created(memoryService.create(accountId, request));
    }

    /** 根据 ID 获取记忆条目。 */
    @GetMapping("/{memoryId}")
    public BaseResponse<MemoryResponse> getById(@PathVariable UUID memoryId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.getById(accountId, memoryId));
    }

    /** 获取记忆列表，可按等级和作用域过滤。 */
    @GetMapping
    public BaseResponse<List<MemoryResponse>> list(@RequestParam(required = false) String level,
                                                    @RequestParam(required = false) String scopeId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.list(accountId, level, scopeId));
    }

    /** 分页查询记忆列表，可按层级和关键词筛选。 */
    @GetMapping("/page")
    public BaseResponse<PageResponse<MemoryResponse>> listPage(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "15") int size,
                                                                @RequestParam(required = false) String level,
                                                                @RequestParam(required = false) String keyword, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.listPage(accountId, page, size, level, keyword));
    }

    /** 更新记忆条目。 */
    @PutMapping("/{memoryId}")
    public BaseResponse<MemoryResponse> update(@PathVariable UUID memoryId,
                                                @Valid @RequestBody UpdateMemoryRequest request, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.update(accountId, memoryId, request));
    }

    /** 删除记忆条目。 */
    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> delete(@PathVariable UUID memoryId, @CurrentAccountId UUID accountId) {
                memoryService.delete(accountId, memoryId);
        return BaseResponse.success();
    }

    /** 批量删除记忆。 */
    @PostMapping("/batch-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public BaseResponse<Void> deleteBatch(@RequestBody java.util.List<UUID> memoryIds,
                                           @CurrentAccountId UUID accountId) {
        memoryService.deleteBatch(memoryIds, accountId);
        return BaseResponse.success();
    }

    /** 切换记忆的重要标记。 */
    @PatchMapping("/{memoryId}/important")
    public BaseResponse<MemoryResponse> toggleImportant(@PathVariable UUID memoryId, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.toggleImportant(accountId, memoryId));
    }

    /** 获取重要记忆列表。 */
    @GetMapping("/important")
    public BaseResponse<List<MemoryResponse>> listImportant(@RequestParam(required = false, defaultValue = "20") Integer limit, @CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.listImportant(accountId, limit));
    }

    /** 获取记忆统计信息。 */
    @GetMapping("/stats")
    public BaseResponse<Map<String, Object>> getStats(@CurrentAccountId UUID accountId) {
                return BaseResponse.success(memoryService.getStats(accountId));
    }
}
