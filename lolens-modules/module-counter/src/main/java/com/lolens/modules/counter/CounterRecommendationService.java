package com.lolens.modules.counter;

import java.util.List;

/**
 * 对位推荐服务：根据敌方已选英雄返回推荐克制英雄。
 */
public interface CounterRecommendationService {

    /** 敌方英雄（key）列表 → 克制建议列表。 */
    List<CounterSuggestion> recommendCounters(List<String> enemyChampionKeys);

    /** 一条克制建议：敌方英雄与其推荐克制英雄。 */
    record CounterSuggestion(String enemyChampion, List<String> counters) {
    }
}
