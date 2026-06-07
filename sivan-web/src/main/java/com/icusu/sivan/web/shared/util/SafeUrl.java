package com.icusu.sivan.web.shared.util;

/**
 * 安全 URL 值对象 — 表示已通过 {@link UrlValidator} 校验的 URL。
 * <p>SAST 工具通过追踪此类型的构造时机判断 URL 是否经过校验：
 * 只有在 {@link UrlValidator#validate(String)} 返回的 {@link ValidationResult#sanitizedUrl()} 才能创建实例。</p>
 */
public final class SafeUrl {

    private final String url;

    /** 包级私有构造 — 仅 {@link UrlValidator.ValidationResult} 内部创建。 */
    SafeUrl(String url) {
        this.url = url;
    }

    /**
     * 返回安全的 URL 字符串。
     */
    public String value() {
        return url;
    }

    @Override
    public String toString() {
        return url;
    }
}
