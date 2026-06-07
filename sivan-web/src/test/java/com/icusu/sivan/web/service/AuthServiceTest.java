package com.icusu.sivan.web.account.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.web.account.dto.LoginRequest;
import com.icusu.sivan.web.account.dto.RegisterRequest;
import com.icusu.sivan.web.account.dto.AuthResponse;
import com.icusu.sivan.web.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/**
 * 认证服务测试。
 */
class AuthServiceTest {

    @Mock
    private IAccountRepository accountRepository;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private IUserProfileRepository userProfileRepository;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    /**
     * 初始化测试环境。
     */
    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(accountRepository, passwordEncoder, knowledgeBaseService, userProfileRepository);
        ReflectionTestUtils.setField(authService, "jwtSecret", "test-secret-key-minimum-256-bits-long-for-hmac-test");
        ReflectionTestUtils.setField(authService, "jwtExpiration", 86400000L);
    }

    /**
     * 注册新用户应成功。
     */
    @Test
    void register_shouldSucceed() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setEmail("test@example.com");

        when(accountRepository.existsByUsername("testuser")).thenReturn(false);
        doAnswer(invocation -> {
            Account a = invocation.getArgument(0);
            if (a.getAccountId() == null) {
                a.setAccountId(UUID.randomUUID());
            }
            return null;
        }).when(accountRepository).save(any(Account.class));

        AuthResponse response = authService.register(request);

        assertNotNull(response.getToken());
        assertEquals("testuser", response.getUsername());
        assertNotNull(response.getAccountId());
        verify(accountRepository).save(any(Account.class));
    }

    /**
     * 用户名已存在时应抛出异常。
     */
    @Test
    void register_shouldThrowWhenUsernameExists() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("existing");
        request.setPassword("password123");

        when(accountRepository.existsByUsername("existing")).thenReturn(true);

        assertThrows(DomainException.class, () -> authService.register(request));
        verify(accountRepository, never()).save(any());
    }

    /**
     * 使用正确凭据登录应成功。
     */
    @Test
    void login_shouldSucceed() {
        UUID accountId = UUID.randomUUID();
        String hash = passwordEncoder.encode("password123");
        Account account = Account.builder()
                .accountId(accountId)
                .username("testuser")
                .passwordHash(hash)
                .displayName("testuser")
                .status(Account.STATUS_ACTIVE)
                .build();

        when(accountRepository.findByUsername("testuser")).thenReturn(Optional.of(account));

        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");

        AuthResponse response = authService.login(request);

        assertNotNull(response.getToken());
        assertEquals(accountId, response.getAccountId());
    }

    /**
     * 用户不存在时应抛出异常。
     */
    @Test
    void login_shouldThrowWhenUserNotFound() {
        when(accountRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setUsername("nobody");
        request.setPassword("password");

        assertThrows(DomainException.class, () -> authService.login(request));
    }

    /**
     * 密码错误时应抛出异常。
     */
    @Test
    void login_shouldThrowWhenPasswordWrong() {
        Account account = Account.builder()
                .username("testuser")
                .passwordHash(passwordEncoder.encode("correct"))
                .build();

        when(accountRepository.findByUsername("testuser")).thenReturn(Optional.of(account));

        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("wrong");

        assertThrows(DomainException.class, () -> authService.login(request));
    }
}
