package com.icusu.sivan.web.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 认证响应 DTO。
 */
@Data
@Builder
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private UUID accountId;
    private String username;
    private String displayName;
    private String preferences;
    private String quota;
}
