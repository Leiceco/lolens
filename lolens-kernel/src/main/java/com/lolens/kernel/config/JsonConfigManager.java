package com.lolens.kernel.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/** 基于 Jackson + 本地 JSON 文件的配置管理实现。 */
public class JsonConfigManager implements ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(JsonConfigManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configDir;

    public JsonConfigManager(Path configDir) {
        this.configDir = configDir;
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            log.warn("创建配置目录失败: {}", configDir, e);
        }
    }

    @Override
    public <T> T load(String moduleId, Class<T> type, T defaultConfig) {
        Path file = configDir.resolve(moduleId + ".json");
        if (!Files.exists(file)) {
            return defaultConfig;
        }
        try {
            String json = Files.readString(file);
            JsonNode defaultsNode = MAPPER.valueToTree(defaultConfig);
            JsonNode parsedNode = MAPPER.readTree(json);
            // 字段级补齐：新版本新增的字段在旧配置中缺失时，使用默认值（不覆盖用户已配置的值）
            ObjectNode merged = mergeMissing(defaultsNode, parsedNode);
            T parsed = MAPPER.treeToValue(merged, type);
            return parsed == null ? defaultConfig : parsed;
        } catch (IOException e) {
            log.warn("配置解析失败 {}，使用默认配置: {}", file, e.getMessage());
            return defaultConfig;
        }
    }

    /** 将 defaults 中 parsed 缺失的顶层字段补齐（递归处理嵌套对象）。 */
    private ObjectNode mergeMissing(JsonNode defaults, JsonNode parsed) {
        if (defaults == null || !defaults.isObject() || parsed == null || !parsed.isObject()) {
            return (ObjectNode) parsed;
        }
        ObjectNode merged = (ObjectNode) parsed.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = defaults.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode parsedValue = merged.get(key);
            JsonNode defaultField = entry.getValue();
            if (parsedValue == null || parsedValue.isNull()) {
                merged.set(key, defaultField.deepCopy());
            } else if (defaultField.isObject() && parsedValue.isObject()) {
                merged.set(key, mergeMissing(defaultField, parsedValue));
            }
        }
        return merged;
    }

    @Override
    public void save(String moduleId, Object config) {
        Path file = configDir.resolve(moduleId + ".json");
        Path tmp = configDir.resolve(moduleId + ".json.tmp");
        try {
            // 先写临时文件再原子替换，避免写入中断（崩溃/断电）损坏既有配置
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("配置保存失败 {}: {}", file, e.getMessage(), e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理临时文件失败可忽略
            }
        }
    }
}
