package com.icusu.sivan.infra.shared.sse;

import java.util.regex.Pattern;

/**
 * SSE 事件内容脱敏工具。
 * <p>过滤 SSE 事件中的敏感信息（API key、密码、JWT token 等），
 * 防止 LLM 输出泄漏凭据。</p>
 */
public class SseSanitizer {

    private static final int MAX_CONTENT_LENGTH = 10_000;

    /** 敏感模式最低长度阈值 — 短于此长度的内容直接跳过扫描。 */
    private static final int SHORT_CONTENT_THRESHOLD = 100;

    // ====== 预编译正则，避免每 chunk 重新编译 ======

    /** OpenAI 风格 API key（sk- 开头 ≥20 字符）。 */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[a-zA-Z0-9]{20,}");

    /** Bearer token。使用原子组避免 ReDoS。 */
    private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer [a-zA-Z0-9_\\-.]+");

    /** URL 参数中的 api_key 或 password。合并检测减少扫描遍数。 */
    private static final Pattern CREDENTIAL_PARAM_PATTERN = Pattern.compile("(api_key|password)[=:]\\s*[^\\s,&]+");

    /** JWT token（eyJ 开头 base64url 三段式）。 */
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** 敏感特征快速预检 — 任一特征出现才执行完整正则扫描。 */
    private static final Pattern QUICK_CHECK = Pattern.compile("sk-|Bearer |api_key[=:]|password[=:]|eyJ");

    private SseSanitizer() {}

    /**
     * 对 SSE 文本内容脱敏：
     * <ul>
     *   <li>OpenAI 风格 API key（sk- 开头 ≥20 字符）</li>
     *   <li>Bearer token</li>
     *   <li>URL 参数中的 api_key / password</li>
     *   <li>JWT token（eyJ 开头 base64url 三段式）</li>
     * </ul>
     * 短内容（<100 字符）且无敏感特征时跳过正则扫描以降低开销。
     */
    public static String sanitize(String content) {
        return truncate(mask(content), MAX_CONTENT_LENGTH);
    }

    private static String mask(String content) {
        if (content == null || content.isBlank()) return "";

        // 短内容快速预检：无敏感特征则跳过正则扫描
        if (content.length() < SHORT_CONTENT_THRESHOLD && !QUICK_CHECK.matcher(content).find()) {
            return content;
        }

        // 预编译正则替换 — 避免每 chunk 重新编译
        String masked = API_KEY_PATTERN.matcher(content).replaceAll("sk-***");
        masked = BEARER_PATTERN.matcher(masked).replaceAll("Bearer ***");
        masked = CREDENTIAL_PARAM_PATTERN.matcher(masked).replaceAll("$1=***");
        masked = JWT_PATTERN.matcher(masked).replaceAll("***JWT***");
        return masked;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
