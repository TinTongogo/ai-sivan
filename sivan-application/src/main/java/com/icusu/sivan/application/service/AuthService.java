package com.icusu.sivan.application.service;

import com.icusu.sivan.common.exception.BusinessException;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.infra.agent.service.ShortIdGenerator;
import com.icusu.sivan.application.account.dto.ChangePasswordRequest;
import com.icusu.sivan.application.account.dto.LoginRequest;
import com.icusu.sivan.application.account.dto.RegisterRequest;
import com.icusu.sivan.application.account.dto.AuthResponse;
import com.icusu.sivan.application.knowledge.KnowledgeBaseService;
import com.icusu.sivan.application.knowledge.dto.CreateKnowledgeBaseRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.UUID.randomUUID;

/** 认证服务，处理用户注册和登录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final IAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IUserProfileRepository userProfileRepository;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /** 用户注册，自动创建默认知识库。 */
    public AuthResponse register(RegisterRequest request) {
        if (accountRepository.existsByUsername(request.getUsername())) {
            throw new DomainException(409, "error.auth.username-exists");
        }

        String shortId = generateAccountShortId();
        Account account = Account.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .displayName(request.getDisplayName() != null ? request.getDisplayName() : request.getUsername())
                .shortId(shortId)
                .status("active")
                .build();

        accountRepository.save(account);
        UUID accountId = account.getAccountId();

        // 创建默认画像（可修改）
        try {
            UserProfile profile = UserProfile.builder()
                    .accountId(accountId)
                    .name(request.getDisplayName())
                    .aiLanguage("auto")
                    .active(true)
                    .autoLearn(true)
                    .build();
            userProfileRepository.save(profile);
        } catch (Exception e) {
            log.warn("创建默认画像失败(不影响注册): {}", e.getMessage());
        }

        // 创建默认知识库（可删除）
        try {
            CreateKnowledgeBaseRequest kbReq = new CreateKnowledgeBaseRequest();
            kbReq.setKbName("默认知识库");
            kbReq.setDescription("系统默认知识库，可存放常用文档");
            knowledgeBaseService.create(accountId, kbReq);
        } catch (Exception e) {
            log.warn("创建默认知识库失败(不影响注册): {}", e.getMessage());
        }

        String token = generateToken(accountId, account.getUsername());
        return AuthResponse.builder()
                .token(token)
                .accountId(accountId)
                .username(account.getUsername())
                .displayName(account.getDisplayName())
                .preferences(account.getPreferences())
                .quota(account.getQuota())
                .build();
    }

    /** 用户登录，生成 JWT。 */
    public AuthResponse login(LoginRequest request) {
        Account account = accountRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new DomainException(400, "error.auth.invalid-credentials"));

        if (!account.isActive()) {
            throw new DomainException(403, "error.auth.account-disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
            throw new DomainException(400, "error.auth.invalid-credentials");
        }

        String token = generateToken(account.getAccountId(), account.getUsername());
        return AuthResponse.builder()
                .token(token)
                .accountId(account.getAccountId())
                .username(account.getUsername())
                .displayName(account.getDisplayName())
                .preferences(account.getPreferences())
                .quota(account.getQuota())
                .build();
    }

    /** 修改密码 — 验证旧密码后更新为新密码，递增 token_version 使旧 JWT 失效。 */
    public void changePassword(UUID accountId, ChangePasswordRequest request) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        if (!passwordEncoder.matches(request.getOldPassword(), account.getPasswordHash())) {
            throw new DomainException(400, "error.auth.old-password-wrong");
        }

        account.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        account.setTokenVersion(account.getTokenVersion() + 1);
        accountRepository.save(account);
    }

    /** 生成全局唯一的账户短标识符。 */
    private String generateAccountShortId() {
        for (int i = 0; i < 5; i++) {
            String candidate = ShortIdGenerator.generate();
            if (!accountRepository.existsByShortId(candidate)) {
                return candidate;
            }
        }
        return ShortIdGenerator.generateWithSuffix();
    }

    // ── 密码找回（内存令牌，无邮件依赖） ──
    private final ConcurrentHashMap<String, PasswordResetEntry> resetTokens = new ConcurrentHashMap<>();

    private record PasswordResetEntry(UUID accountId, Instant expiresAt) {}

    /** 生成密码重置令牌，打印到日志（无邮件服务时的替代方案）。 */
    public String generatePasswordResetToken(String username) {
        Account account = accountRepository.findByUsername(username)
                .orElseThrow(() -> new DomainException(400, "error.auth.username-not-found"));
        String token = randomUUID().toString().replace("-", "") + randomUUID().toString().replace("-", "");
        resetTokens.put(token, new PasswordResetEntry(account.getAccountId(), Instant.now().plus(Duration.ofMinutes(30))));
        return token;
    }

    /** 使用令牌重置密码。 */
    public void resetPassword(String token, String newPassword) {
        PasswordResetEntry entry = resetTokens.get(token);
        if (entry == null) {
            throw new DomainException(400, "error.auth.reset-token-invalid");
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            resetTokens.remove(token);
            throw new DomainException(400, "error.auth.reset-token-expired");
        }
        Account account = accountRepository.findById(entry.accountId())
                .orElseThrow(() -> new DomainException(400, "error.auth.account-not-found"));
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setTokenVersion(account.getTokenVersion() + 1);
        accountRepository.save(account);
        resetTokens.remove(token);
        log.info("密码已重置: accountId={}", entry.accountId());
    }

    /** 生成 JWT Token（含 jti 唯一标识 + tver 版本号，用于撤销校验）。 */
    private String generateToken(UUID accountId, String username) {
        Account account = accountRepository.findById(accountId).orElse(null);
        int tver = account != null ? account.getTokenVersion() : 0;
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(randomUUID().toString())          // jti: 唯一标识
                .subject(accountId.toString())
                .claim("username", username)
                .claim("role", "user")
                .claim("tver", tver)                  // token version: 改密码后递增
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(key)
                .compact();
    }
}
