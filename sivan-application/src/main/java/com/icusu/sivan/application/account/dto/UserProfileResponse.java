package com.icusu.sivan.application.account.dto;

import com.icusu.sivan.domain.account.UserProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 用户画像响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class UserProfileResponse {
    private UUID profileId;
    private UUID accountId;
    private String name;
    private String bio;
    private String aiLanguage;
    private List<String> expertise;
    private boolean autoLearn;
    private boolean active;

    public static UserProfileResponse from(UserProfile profile) {
        return UserProfileResponse.builder()
                .profileId(profile.getProfileId())
                .accountId(profile.getAccountId())
                .name(profile.getName())
                .bio(profile.getBio())
                .aiLanguage(profile.getAiLanguage())
                .expertise(profile.getExpertise())
                .autoLearn(profile.isAutoLearn())
                .active(profile.isActive())
                .build();
    }
}
