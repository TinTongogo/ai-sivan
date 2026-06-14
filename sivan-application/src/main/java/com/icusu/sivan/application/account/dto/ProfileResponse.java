package com.icusu.sivan.application.account.dto;

import com.icusu.sivan.domain.account.Account;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class ProfileResponse {
    private UUID accountId;
    private String username;
    private String email;
    private String displayName;
    private String preferences;
    private String quota;
    private String status;

    public static ProfileResponse from(Account account) {
        return ProfileResponse.builder()
                .accountId(account.getAccountId())
                .username(account.getUsername())
                .email(account.getEmail())
                .displayName(account.getDisplayName())
                .preferences(account.getPreferences())
                .quota(account.getQuota())
                .status(account.getStatus())
                .build();
    }
}
