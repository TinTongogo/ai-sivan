package com.icusu.sivan.web.agent.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.infra.agent.entity.ProjectEntity;
import com.icusu.sivan.web.agent.dto.LocalPathUpdateRequest;
import com.icusu.sivan.web.agent.dto.ProjectCreateRequest;
import com.icusu.sivan.web.agent.dto.ProjectRenameRequest;
import com.icusu.sivan.web.file.dto.FileEntryResponse;
import com.icusu.sivan.web.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.List;
import java.util.UUID;

/**
 * 项目群组管理控制器。
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /** 获取群组列表。 */
    @GetMapping
    public BaseResponse<List<ProjectEntity>> list(@CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.list(accountId));
    }

    /** 创建群组。 */
    @PostMapping
    public BaseResponse<ProjectEntity> create(@Valid @RequestBody ProjectCreateRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.create(accountId, request.getName()));
    }

    /** 重命名群组。 */
    @PutMapping("/{id}")
    public BaseResponse<ProjectEntity> rename(@PathVariable UUID id, @Valid @RequestBody ProjectRenameRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.rename(accountId, id, request.getName()));
    }

    /** 删除群组。 */
    @DeleteMapping("/{id}")
    public BaseResponse<Void> delete(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean removeFiles, @CurrentAccountId UUID accountId) {
        groupService.delete(accountId, id, removeFiles);
        return BaseResponse.success(null);
    }

    /** 归档群组。 */
    @PostMapping("/{id}/archive")
    public BaseResponse<ProjectEntity> archive(@PathVariable UUID id, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.archive(accountId, id));
    }

    /** 取消归档群组。 */
    @PostMapping("/{id}/unarchive")
    public BaseResponse<ProjectEntity> unarchive(@PathVariable UUID id, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.unarchive(accountId, id));
    }

    /** 更新本地路径。 */
    @PutMapping("/{id}/local-path")
    public BaseResponse<ProjectEntity> updateLocalPath(@PathVariable UUID id, @Valid @RequestBody LocalPathUpdateRequest request, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.updateLocalPath(accountId, id, request.getLocalPath()));
    }

    /** 获取本地路径下的文件列表。 */
    @GetMapping("/{id}/files")
    public BaseResponse<List<FileEntryResponse>> listFiles(
            @PathVariable UUID id, @RequestParam(required = false) String subPath, @CurrentAccountId UUID accountId) {
        return BaseResponse.success(groupService.listFiles(accountId, id, subPath));
    }

    /** 在系统文件管理器中打开项目本地目录。 */
    @PostMapping("/{id}/open-directory")
    public BaseResponse<Void> openDirectory(@PathVariable UUID id, @CurrentAccountId UUID accountId) {
        groupService.openDirectory(accountId, id);
        return BaseResponse.success(null);
    }
}
