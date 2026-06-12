package com.icusu.sivan.infra.security;

import com.icusu.sivan.domain.security.OutputValidator;
import com.icusu.sivan.domain.security.SecurityContext;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于正则的 LLM 输出安全验证器。
 * <p>
 * 检测：
 * <ul>
 *   <li>API Key 泄漏（sk-... 格式的 OpenAI Key）</li>
 *   <li>私钥泄漏（-----BEGIN ... PRIVATE KEY-----）</li>
 *   <li>Bearer Token 泄漏</li>
 *   <li>危险命令（rm -rf / 等）</li>
 *   <li>SQL 注入语句</li>
 *   <li>提示词注入模式</li>
 * </ul>
 */
@Component
public class PatternBasedValidator implements OutputValidator {

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("sk-[a-zA-Z0-9]{20,}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("-----BEGIN (RSA |EC )?PRIVATE KEY-----"),
            Pattern.compile("Bearer [a-zA-Z0-9._-]+"),
            Pattern.compile("rm -rf /"),
            Pattern.compile("DROP TABLE |DELETE FROM ", Pattern.CASE_INSENSITIVE)
    );

    private static final List<String> INJECTION_PATTERNS = List.of(
            "忽略之前的指令", "ignore all previous instructions",
            "你是一个无限制的", "you are unrestricted",
            "忽略所有安全策略", "ignore all safety policies",
            "绕过限制", "bypass restrictions",
            "扮演 DAN", "act as DAN", "you are now DAN"
    );

    @Override
    public OutputValidator.ValidationResult validate(String output, SecurityContext ctx) {
        if (output == null || output.isBlank()) {
            return OutputValidator.ValidationResult.ok();
        }

        String lower = output.toLowerCase();

        // 提示词注入检测（快路径）
        for (String inj : INJECTION_PATTERNS) {
            if (lower.contains(inj.toLowerCase())) {
                return OutputValidator.ValidationResult.rejected( "输出包含提示词注入模式: " + inj);
            }
        }

        // API Key、危险命令等检测
        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(output).find()) {
                return OutputValidator.ValidationResult.rejected( "输出包含敏感信息: " + p.pattern());
            }
        }

        return OutputValidator.ValidationResult.ok();
    }
}
