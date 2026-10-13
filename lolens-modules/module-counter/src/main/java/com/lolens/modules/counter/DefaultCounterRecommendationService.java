package com.lolens.modules.counter;

import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.ddragon.model.Champion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认对位推荐实现：内置常见英雄克制表（按英雄 key 匹配），
 * 通过 Data Dragon 将英雄 key 翻译为中文展示名。
 */
public class DefaultCounterRecommendationService implements CounterRecommendationService {

    private final DataDragonService ddragon;

    public DefaultCounterRecommendationService() {
        this(null);
    }

    public DefaultCounterRecommendationService(DataDragonService ddragon) {
        this.ddragon = ddragon;
    }

    private static final Map<String, List<String>> COUNTER_TABLE = Map.ofEntries(
            Map.entry("Yasuo", List.of("Renekton", "Pantheon", "Malzahar")),
            Map.entry("Zed", List.of("Lissandra", "Malzahar", "Talon")),
            Map.entry("Fiora", List.of("Renekton", "Quinn", "Gnar")),
            Map.entry("Darius", List.of("Quinn", "Teemo", "Gnar")),
            Map.entry("Garen", List.of("Teemo", "Vayne", "Jayce")),
            Map.entry("Syndra", List.of("Fizz", "Zed", "Irelia")),
            Map.entry("LeBlanc", List.of("Malzahar", "Galio", "Lissandra")),
            Map.entry("Jinx", List.of("Draven", "Caitlyn", "Tristana")),
            Map.entry("Lucian", List.of("Ashe", "Varus", "Jhin")),
            Map.entry("Vayne", List.of("Caitlyn", "Lucian", "Draven")),
            Map.entry("LeeSin", List.of("Graves", "Vi", "KhaZix")),
            Map.entry("KhaZix", List.of("LeeSin", "Rammus", "Udyr")),
            Map.entry("Ekko", List.of("Kassadin", "Fizz", "Malzahar")),
            Map.entry("Azir", List.of("Zed", "Kassadin", "LeBlanc")),
            Map.entry("Caitlyn", List.of("Draven", "Lucian", "Jinx"))
    );

    @Override
    public List<CounterSuggestion> recommendCounters(List<String> enemyChampionKeys) {
        List<CounterSuggestion> result = new ArrayList<>();
        if (enemyChampionKeys == null) {
            return result;
        }
        for (String enemyKey : enemyChampionKeys) {
            List<String> rawCounters = COUNTER_TABLE.get(enemyKey);
            if (rawCounters == null || rawCounters.isEmpty()) {
                continue;
            }
            List<String> displayNames = rawCounters.stream()
                    .map(this::displayName)
                    .toList();
            result.add(new CounterSuggestion(displayName(enemyKey), displayNames));
        }
        return result;
    }

    private String displayName(String key) {
        if (ddragon == null) {
            return key;
        }
        return ddragon.championBySlug(key.toLowerCase())
                .map(Champion::name)
                .orElse(key);
    }
}
