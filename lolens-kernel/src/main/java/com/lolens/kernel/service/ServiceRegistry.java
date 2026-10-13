package com.lolens.kernel.service;

import java.util.Optional;

/** 服务注册表：模块按接口注册实现，其他模块按接口查找。 */
public interface ServiceRegistry {

    /** 注册服务实现。 */
    <T> void register(Class<T> type, T impl);

    /** 按接口查找服务。 */
    <T> Optional<T> find(Class<T> type);

    /** 强制查找，未找到抛异常。 */
    default <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException("服务未注册: " + type.getName()));
    }
}
