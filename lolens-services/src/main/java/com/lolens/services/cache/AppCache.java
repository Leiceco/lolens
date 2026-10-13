package com.lolens.services.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用缓存：封装 Caffeine，供 OP.GG 响应、英雄数据等使用。
 * 采用 JSON 序列化存储，支持跨线程读写与类型还原。
 */
public final class AppCache {

    private static final AppCache INSTANCE = new AppCache();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    private AppCache() {
    }

    public static AppCache instance() {
        return INSTANCE;
    }

    public void put(String key, Object value) {
        try {
            cache.put(key, MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            // 序列化失败不阻断业务
        }
    }

    public <T> T get(String key, Class<T> type) {
        String json = cache.getIfPresent(key);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }
}
