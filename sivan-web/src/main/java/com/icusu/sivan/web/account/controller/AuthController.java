package com.icusu.sivan.web.account.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.application.account.dto.LoginRequest;
import com.icusu.sivan.application.account.dto.RegisterRequest;
import com.icusu.sivan.application.account.dto.AuthResponse;
import com.icusu.sivan.application.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器（注册、登录、密码找回）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 用户注册。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return BaseResponse.created(authService.register(request));
    }

    /** 用户登录。 */
    @PostMapping("/login")
    public BaseResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return BaseResponse.success(authService.login(request));
    }

    /** 请求密码重置（生成令牌，打印到服务端日志）。 */
    @PostMapping("/password-reset/request")
    public BaseResponse<String> requestPasswordReset(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            return BaseResponse.badRequest("用户名不能为空");
        }
        String token = authService.generatePasswordResetToken(username);
        return BaseResponse.success(token);
    }

    /** 使用令牌重置密码。 */
    @PostMapping("/password-reset/reset")
    public BaseResponse<Void> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (token == null || newPassword == null || newPassword.isBlank()) {
            return BaseResponse.badRequest("参数不完整");
        }
        authService.resetPassword(token, newPassword);
        return BaseResponse.success();
    }
}
