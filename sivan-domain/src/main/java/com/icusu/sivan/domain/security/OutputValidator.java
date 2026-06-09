package com.icusu.sivan.domain.security;

/** 输出验证器 — 检查 LLM 输出是否安全。 */
@FunctionalInterface
public interface OutputValidator {
    ValidationResult validate(String output, SecurityContext ctx);

    record ValidationResult(boolean passed, String reason) {
        public static ValidationResult ok() { return new ValidationResult(true, null); }
        public static ValidationResult rejected(String reason) { return new ValidationResult(false, reason); }
    }
}
