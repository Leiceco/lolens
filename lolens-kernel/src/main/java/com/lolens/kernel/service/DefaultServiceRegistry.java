package com.lolens.kernel.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 默认服务注册表实现。 */
public class DefaultServiceRegistry implements ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> void register(Class<T> type, T impl) {
        if (impl == null) {
            throw new IllegalArgumentException("服务实现不能为 null: " + type.getName());
        }
        services.put(type, impl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable((T) services.get(type));
    }
}
