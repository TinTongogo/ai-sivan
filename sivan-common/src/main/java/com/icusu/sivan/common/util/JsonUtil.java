package com.icusu.sivan.common.util;

/**
 * JSON 工具类。
 */
public class JsonUtil {

    private JsonUtil() {}

    /**
     * 转义 JSON 字符串值中的特殊字符。
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
