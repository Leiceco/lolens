package com.lolens.kernel.config;

/** 配置管理：每模块独立配置命名空间，序列化为 config/&lt;moduleId&gt;.json。 */
public interface ConfigManager {

    /** 加载模块配置；不存在或解析失败时返回 defaultConfig。 */
    <T> T load(String moduleId, Class<T> type, T defaultConfig);

    /** 保存模块配置。 */
    void save(String moduleId, Object config);
}
