package com.icusu.sivan.core.verification;

public record VerificationResult(boolean passed, String reason) {
    public static final VerificationResult PASSED = new VerificationResult(true, null);

    public static VerificationResult failed(String reason) {
        return new VerificationResult(false, reason);
    }
}
