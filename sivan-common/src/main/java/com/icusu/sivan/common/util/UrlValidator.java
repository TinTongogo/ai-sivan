package com.icusu.sivan.common.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * URL 安全校验工具 — 防止 SSRF、协议走私等攻击。
 * <p>提供两级校验：
 * <ul>
 *   <li>{@link #validate(String)} — 基础格式校验（协议、字符、凭证）</li>
 *   <li>{@link #validatePrivateAccess(String)} — 严苛模式，额外校验目标 IP 为私有地址，
 *       防止 SSRF 访问外部或云元数据端点</li>
 * </ul>
 * </p>
 */
public final class UrlValidator {

    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    /** 控制字符（ASCII 0-31）及常见注入字符。 */
    private static final Pattern INVALID_CHARS = Pattern.compile("[\\x00-\\x1f<>\"'\\\\]");

    /** 检测 URL 中嵌入的凭证信息。使用原子组避免 ReDoS。 */
    private static final Pattern HAS_CREDENTIALS = Pattern.compile("://[^@]++@");

    /** IPv6 唯一本地地址前缀 fc00::/7。 */
    private static final int IPV6_ULA_PREFIX = 0xfc;

    private UrlValidator() {}

    /**
     * 校验外部请求 URL 的安全性（基础模式）。
     * <p>验证协议白名单（http/https）、字符安全、无内嵌凭证、主机格式。</p>
     *
     * @param url 用户传入的 URL
     * @return 校验结果
     */
    public static ValidationResult validate(String url) {
        if (url == null || url.isBlank()) {
            return ValidationResult.invalid("URL 不能为空");
        }

        String trimmed = url.strip();

        // 检查无效字符
        if (INVALID_CHARS.matcher(trimmed).find()) {
            return ValidationResult.invalid("URL 包含无效字符");
        }

        // 检查内嵌凭证（eg http://user:pass@host）
        if (HAS_CREDENTIALS.matcher(trimmed).find()) {
            return ValidationResult.invalid("URL 不允许包含凭证信息");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return ValidationResult.invalid("URL 格式无效: " + e.getMessage());
        }

        // 检查协议
        String scheme = uri.getScheme();
        if (scheme == null) {
            return ValidationResult.invalid("URL 缺少协议类型");
        }
        if (ALLOWED_SCHEMES.stream().noneMatch(s -> s.equalsIgnoreCase(scheme))) {
            return ValidationResult.invalid("不支持的协议: " + scheme);
        }

        // 检查主机
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.invalid("URL 缺少主机地址");
        }

        return ValidationResult.valid(trimmed);
    }

    /**
     * 严苛模式校验 — URL 必须指向私有/本地网络。
     * <p>在 {@link #validate(String)} 基础上，进一步解析主机名到 IP 地址，
     * 确认所有解析结果均为环路地址或站点本地地址（即私有网络），
     * 拒绝链路本地地址（含云元数据端点 169.254.169.254）和公网地址。</p>
     * <p>每次调用均重新 DNS 解析，可防范 DNS rebinding 攻击。</p>
     *
     * @param url 用户传入的 URL
     * @return 校验结果（失败含具体原因）
     */
    public static ValidationResult validatePrivateAccess(String url) {
        var base = validate(url);
        if (!base.valid()) {
            return base;
        }

        String host;
        try {
            URI uri = new URI(url);
            host = uri.getHost();
        } catch (URISyntaxException e) {
            return ValidationResult.invalid("URL 解析失败: " + e.getMessage());
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return ValidationResult.invalid("主机无法解析到任何 IP 地址: " + host);
            }
            for (InetAddress addr : addresses) {
                if (!isPrivateAddress(addr)) {
                    return ValidationResult.invalid(
                            "不允许访问非私有地址: " + host + " (" + addr.getHostAddress() + ")");
                }
            }
            return ValidationResult.valid(url);
        } catch (UnknownHostException e) {
            return ValidationResult.invalid("无法解析主机地址: " + host + " — " + e.getMessage());
        }
    }

    /**
     * 判断 IP 地址是否为允许的私有/环路地址。
     * <ul>
     *   <li>环路地址（127.0.0.0/8, ::1）— 允许</li>
     *   <li>站点本地地址（10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16）— 允许</li>
     *   <li>IPv6 唯一本地地址（fc00::/7）— 允许</li>
     *   <li>链路本地地址（169.254.0.0/16, fe80::/10）— 拒绝（含云元数据端点）</li>
     *   <li>公网地址 — 拒绝</li>
     * </ul>
     */
    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7（Java isSiteLocalAddress 不覆盖此范围）
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xfe) == IPV6_ULA_PREFIX;
        }
        return false;
    }

    /** 校验结果。 */
    public record ValidationResult(boolean valid, String sanitizedUrl, String errorMessage) {
        public static ValidationResult valid(String url) {
            return new ValidationResult(true, url, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, null, message);
        }
    }
}
