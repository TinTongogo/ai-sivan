package com.icusu.sivan.infra.agent.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 短标识符生成器，生成 Docker 风格的 adjective-noun 组合作为项目短 ID。
 * 格式：{adjective}-{noun}，碰撞时追加 3 位随机后缀。
 */
public final class ShortIdGenerator {

    private static final String[] ADJECTIVES = {
            "swift", "bold", "calm", "dark", "eager", "golden", "happy", "keen",
            "lively", "merry", "noble", "brave", "crisp", "deep", "elite", "fresh",
            "grand", "light", "quiet", "royal", "sharp", "tender", "vivid", "warm",
            "young", "orange", "purple", "silver", "misty", "sunny", "green", "amber"
    };

    private static final String[] NOUNS = {
            "dawn", "eagle", "fox", "hawk", "lion", "moon", "oak", "palm",
            "reef", "star", "tide", "vale", "wave", "wolf", "bear", "cloud",
            "deer", "flame", "glen", "hill", "iris", "jade", "koi", "lake",
            "moss", "nest", "peak", "rain", "snow", "tree", "wind", "leaf"
    };

    private static final int MAX_RETRIES = 5;
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";

    private ShortIdGenerator() {}

    /** 生成一个 adjective-noun 组合。 */
    public static String generate() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return ADJECTIVES[rng.nextInt(ADJECTIVES.length)]
                + "-" + NOUNS[rng.nextInt(NOUNS.length)];
    }

    /**
     * 生成一个 ajdective-noun-{3位后缀} 组合，用于碰撞回退。
     * 后缀为 3 位随机小写字母或数字。
     */
    public static String generateWithSuffix() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(generate());
        sb.append('-');
        for (int i = 0; i < 3; i++) {
            sb.append(ALPHANUMERIC.charAt(rng.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}
