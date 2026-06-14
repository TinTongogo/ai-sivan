package com.icusu.sivan.infra.prompt;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptCache {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    public String get(String k) { return cache.get(k); }
    public void put(String k, String v) { cache.put(k, v); }
    public void invalidate(String k) { cache.remove(k); }
    public static String cacheKey(String packId, int version) { return "prompt:" + packId + ":v" + version; }
    public void clear() { cache.clear(); }
}
