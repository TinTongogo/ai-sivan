package com.icusu.sivan.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UrlValidator} 单元测试。
 * <p>覆盖基础校验与 SSRF 私有地址白名单校验。</p>
 */
class UrlValidatorTest {

    // ========== validatePrivateAccess — 合法私有地址 ==========

    @Test
    void validatePrivateAccess_shouldAcceptLoopbackIPv4() {
        var result = UrlValidator.validatePrivateAccess("http://127.0.0.1:11434");
        assertTrue(result.valid(), "127.0.0.1 环路地址应允许");
    }

    @Test
    void validatePrivateAccess_shouldAcceptLocalhostHostname() {
        var result = UrlValidator.validatePrivateAccess("http://localhost:11434/v1");
        assertTrue(result.valid(), "localhost 主机名应允许");
    }

    @Test
    void validatePrivateAccess_shouldAcceptIPv6Loopback() {
        var result = UrlValidator.validatePrivateAccess("http://[::1]:11434");
        assertTrue(result.valid(), "IPv6 环路地址 ::1 应允许");
    }

    @Test
    void validatePrivateAccess_shouldAcceptPrivate10Range() {
        var result = UrlValidator.validatePrivateAccess("http://10.0.0.1:8080");
        assertTrue(result.valid(), "10.0.0.0/8 私有地址应允许");
    }

    @Test
    void validatePrivateAccess_shouldAcceptPrivate192168Range() {
        var result = UrlValidator.validatePrivateAccess("http://192.168.1.100:8080");
        assertTrue(result.valid(), "192.168.0.0/16 私有地址应允许");
    }

    @Test
    void validatePrivateAccess_shouldAcceptPrivate172Range() {
        assertTrue(UrlValidator.validatePrivateAccess("http://172.16.0.1:8080").valid(),
                "172.16.0.0/12 起始地址应允许");
        assertTrue(UrlValidator.validatePrivateAccess("http://172.31.255.255:8080").valid(),
                "172.16.0.0/12 结束地址应允许");
    }

    @Test
    void validatePrivateAccess_shouldAcceptIPv6UniqueLocal() {
        // fc00::/7 — IPv6 唯一本地地址
        var result = UrlValidator.validatePrivateAccess("http://[fd00::1]:11434");
        assertTrue(result.valid(), "IPv6 唯一本地地址 fd00::/8 应允许");
    }

    // ========== validatePrivateAccess — 非法地址 ==========

    @Test
    void validatePrivateAccess_shouldRejectPublicIP() {
        var result = UrlValidator.validatePrivateAccess("http://8.8.8.8");
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("非私有地址"));
    }

    @Test
    void validatePrivateAccess_shouldRejectLinkLocal() {
        var result = UrlValidator.validatePrivateAccess("http://169.254.169.254/latest/meta-data");
        assertFalse(result.valid(), "链路本地地址 169.254.0.0/16 应拒绝（含云元数据端点）");
        assertTrue(result.errorMessage().contains("非私有地址"));
    }

    // ========== validatePrivateAccess — 基础格式校验 ==========

    @Test
    void validatePrivateAccess_shouldRejectNull() {
        assertFalse(UrlValidator.validatePrivateAccess(null).valid());
    }

    @Test
    void validatePrivateAccess_shouldRejectBlank() {
        assertFalse(UrlValidator.validatePrivateAccess("  ").valid());
    }

    @Test
    void validatePrivateAccess_shouldRejectInvalidChars() {
        assertFalse(UrlValidator.validatePrivateAccess("http://127.0.0.1/path\">attack").valid());
    }

    @Test
    void validatePrivateAccess_shouldRejectEmbeddedCredentials() {
        assertFalse(UrlValidator.validatePrivateAccess("http://user:pass@127.0.0.1").valid());
    }

    @Test
    void validatePrivateAccess_shouldRejectWrongProtocol() {
        assertFalse(UrlValidator.validatePrivateAccess("file:///etc/passwd").valid());
        assertFalse(UrlValidator.validatePrivateAccess("ftp://192.168.1.1").valid());
    }

    @Test
    void validatePrivateAccess_shouldRejectMissingHost() {
        assertFalse(UrlValidator.validatePrivateAccess("http://").valid());
    }

    // ========== validatePrivateAccess — 边界情况 ==========

    @Test
    void validatePrivateAccess_shouldRejectUnresolvableHost() {
        var result = UrlValidator.validatePrivateAccess("http://this-hostname-does-not-exist-99999.local");
        assertFalse(result.valid(), "无法解析的主机名应拒绝");
        assertTrue(result.errorMessage().contains("无法解析"));
    }

    @Test
    void validatePrivateAccess_shouldAcceptHttps() {
        var result = UrlValidator.validatePrivateAccess("https://127.0.0.1:443");
        assertTrue(result.valid(), "HTTPS 协议应允许");
    }

    // ========== validate（基础模式）保持向后兼容 ==========

    @Test
    void validate_shouldAcceptPublicIP() {
        // 基础模式不拦截公网地址
        assertTrue(UrlValidator.validate("http://8.8.8.8").valid());
    }

    @Test
    void validate_shouldAcceptLinkLocal() {
        // 基础模式不拦截链路本地地址
        assertTrue(UrlValidator.validate("http://169.254.169.254").valid());
    }

    @Test
    void validate_shouldRejectNull() {
        assertFalse(UrlValidator.validate(null).valid());
    }

    @Test
    void validate_shouldRejectBlank() {
        assertFalse(UrlValidator.validate("  ").valid());
    }

    @Test
    void validate_shouldRejectWrongProtocol() {
        assertFalse(UrlValidator.validate("file:///etc/passwd").valid());
    }

    @Test
    void validate_shouldRejectEmbeddedCredentials() {
        assertFalse(UrlValidator.validate("http://user:pass@10.0.0.1").valid());
    }
}
