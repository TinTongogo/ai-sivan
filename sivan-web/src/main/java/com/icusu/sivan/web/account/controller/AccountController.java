package com.icusu.sivan.web.account.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.common.exception.BusinessException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IProfileChangeLogRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.ProfileChangeLog;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.web.account.dto.ChangePasswordRequest;
import com.icusu.sivan.web.account.dto.ProfileResponse;
import com.icusu.sivan.web.account.dto.UpdateProfileRequest;
import com.icusu.sivan.web.account.dto.UpdateUserProfileRequest;
import com.icusu.sivan.web.account.dto.UserProfileResponse;
import com.icusu.sivan.web.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.web.shared.security.CurrentAccountId;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;
    private final IAccountRepository accountRepository;
    private final IUserProfileRepository userProfileRepository;
    private final IProfileChangeLogRepository profileChangeLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 修改密码。 */
    @PutMapping("/password")
    public BaseResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, @CurrentAccountId UUID accountId) {
                authService.changePassword(accountId, request);
        return BaseResponse.success(null);
    }

    /** 获取账号信息（含 quota、preferences）。 */
    @GetMapping("/profile")
    public BaseResponse<ProfileResponse> getProfile(@CurrentAccountId UUID accountId) {
                Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        return BaseResponse.success(ProfileResponse.from(account));
    }

    /** 更新个人信息（displayName、preferences 等）。 */
    @PutMapping("/profile")
    public BaseResponse<ProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request, @CurrentAccountId UUID accountId) {
                Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        if (request.getDisplayName() != null) account.setDisplayName(request.getDisplayName());
        if (request.getPreferences() != null) account.setPreferences(request.getPreferences());
        if (request.getQuota() != null) account.setQuota(request.getQuota());

        accountRepository.save(account);
        return BaseResponse.success(ProfileResponse.from(account));
    }

    /** 获取用户 AI 画像（UserProfile）。 */
    @GetMapping("/profile/ai")
    public BaseResponse<UserProfileResponse> getAiProfile(@CurrentAccountId UUID accountId) {
        UserProfile profile = userProfileRepository.findByAccountId(accountId)
                .orElseGet(() -> UserProfile.builder()
                        .accountId(accountId)
                        .aiLanguage("auto")
                        .active(true)
                        .autoLearn(true)
                        .build());
        return BaseResponse.success(UserProfileResponse.from(profile));
    }

    /** 更新用户 AI 画像。 */
    @PutMapping("/profile/ai")
    public BaseResponse<UserProfileResponse> updateAiProfile(@Valid @RequestBody UpdateUserProfileRequest request,
                                                              @CurrentAccountId UUID accountId) {
        UserProfile profile = userProfileRepository.findByAccountId(accountId)
                .orElseGet(() -> UserProfile.builder()
                        .accountId(accountId)
                        .aiLanguage("auto")
                        .active(true)
                        .autoLearn(true)
                        .build());

        // 记录变更日志
        String source = Boolean.TRUE.equals(request.getAutoLearn()) ? "manual" : "manual";
        if (request.getName() != null && !request.getName().equals(profile.getName())) {
            profileChangeLogRepository.save(ProfileChangeLog.of(accountId, source, "name", profile.getName(), request.getName()));
            profile.setName(request.getName());
        }
        if (request.getBio() != null && !request.getBio().equals(profile.getBio())) {
            profileChangeLogRepository.save(ProfileChangeLog.of(accountId, source, "bio", profile.getBio(), request.getBio()));
            profile.setBio(request.getBio());
        }
        if (request.getAiLanguage() != null && !request.getAiLanguage().equals(profile.getAiLanguage())) {
            profileChangeLogRepository.save(ProfileChangeLog.of(accountId, source, "aiLanguage", profile.getAiLanguage(), request.getAiLanguage()));
            profile.setAiLanguage(request.getAiLanguage());
        }
        if (request.getExpertise() != null && !request.getExpertise().equals(profile.getExpertise())) {
            profileChangeLogRepository.save(ProfileChangeLog.of(accountId, source, "expertise",
                    profile.getExpertise() != null ? String.join(", ", profile.getExpertise()) : null,
                    String.join(", ", request.getExpertise())));
            profile.setExpertise(request.getExpertise());
        }
        if (request.getAutoLearn() != null && !request.getAutoLearn().equals(profile.isAutoLearn())) {
            profileChangeLogRepository.save(ProfileChangeLog.of(accountId, source, "autoLearn",
                    String.valueOf(profile.isAutoLearn()), String.valueOf(request.getAutoLearn())));
            profile.setAutoLearn(request.getAutoLearn());
        }

        UserProfile saved = userProfileRepository.save(profile);
        return BaseResponse.success(UserProfileResponse.from(saved));
    }

    /** 获取画像变更历史。 */
    @GetMapping("/profile/ai/history")
    public BaseResponse<List<ProfileChangeLog>> getAiProfileHistory(@CurrentAccountId UUID accountId) {
        List<ProfileChangeLog> history = profileChangeLogRepository.findByAccountId(accountId, 20);
        return BaseResponse.success(history);
    }

    /** 获取 UI 偏好（从 preferences JSON 解析）。 */
    @GetMapping("/preferences")
    public BaseResponse<Map<String, Object>> getPreferences(@CurrentAccountId UUID accountId) {
                Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        Map<String, Object> preferences = Map.of();
        if (account.getPreferences() != null && !account.getPreferences().isBlank()) {
            try {
                preferences = objectMapper.readValue(account.getPreferences(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }
        return BaseResponse.success(preferences);
    }

    /** 更新 UI 偏好。 */
    @PutMapping("/preferences")
    public BaseResponse<Void> updatePreferences(@RequestBody Map<String, Object> preferences, @CurrentAccountId UUID accountId) {
        if (preferences != null && preferences.size() > 50) {
            throw BusinessException.badRequest("偏好设置项不能超过 50 个");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        try {
            String json = objectMapper.writeValueAsString(preferences);
            if (json.length() > 8192) {
                throw BusinessException.badRequest("偏好设置序列化后长度不能超过 8192 个字符");
            }
            account.setPreferences(json);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("序列化偏好设置失败", e);
        }
        accountRepository.save(account);
        return BaseResponse.success(null);
    }
}
