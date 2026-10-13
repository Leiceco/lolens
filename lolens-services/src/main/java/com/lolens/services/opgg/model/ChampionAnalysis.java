package com.lolens.services.opgg.model;

import java.util.List;

/**
 * 英雄分析数据（OP.GG MCP 返回的精简模型）：推荐出装、核心装备与技能加点。
 */
public record ChampionAnalysis(
        String championSlug,
        Position position,
        List<RecommendedItem> recommendedItems,
        List<RecommendedRune> recommendedRunes,
        String summary,
        List<String> skillOrder
) {

    /** 技能加点序列（1-18 级，如 [Q,E,Q,W,Q,R,...]）；为空表示 OP.GG 未提供，需本地规则生成。 */
    public boolean hasSkillOrder() {
        return skillOrder != null && !skillOrder.isEmpty();
    }

    public record RecommendedItem(int itemId, String name, String tier) {
    }

    /**
     * 推荐符文页。
     *
     * @param name              主页面名称（如 Sorcery）
     * @param keystone          基石符文（主页面第一个符文）
     * @param secondaryPageName 副页面名称（可为空）
     * @param secondaryRunes    副页面符文列表（可为空）
     * @param primaryRunes      主页面全部符文名称（含基石，可为空）
     * @param primaryStyleId    主页面 style id（OP.GG primary_page_id，可为空）
     * @param primaryRuneIds    主页面符文 id 列表（含基石，可为空）
     * @param secondaryStyleId  副页面 style id（OP.GG secondary_page_id，可为空）
     * @param secondaryRuneIds  副页面符文 id 列表（可为空）
     * @param statModIds        小符文 id 列表（stat_mod_ids，可为空）
     */
    public record RecommendedRune(
            String name,
            String keystone,
            String secondaryPageName,
            List<String> secondaryRunes,
            List<String> primaryRunes,
            Integer primaryStyleId,
            List<Integer> primaryRuneIds,
            Integer secondaryStyleId,
            List<Integer> secondaryRuneIds,
            List<Integer> statModIds
    ) {

        public RecommendedRune {
            primaryRunes = primaryRunes == null ? List.of() : List.copyOf(primaryRunes);
            secondaryRunes = secondaryRunes == null ? List.of() : List.copyOf(secondaryRunes);
            primaryRuneIds = primaryRuneIds == null ? List.of() : List.copyOf(primaryRuneIds);
            secondaryRuneIds = secondaryRuneIds == null ? List.of() : List.copyOf(secondaryRuneIds);
            statModIds = statModIds == null ? List.of() : List.copyOf(statModIds);
        }

        /** 兼容旧构造：仅名称信息（无 id），id 链路由 Data Dragon 名称映射兜底。 */
        public RecommendedRune(String name, String keystone, String secondaryPageName, List<String> secondaryRunes) {
            this(name, keystone, secondaryPageName, secondaryRunes,
                    List.of(), null, List.of(), null, List.of(), List.of());
        }
    }
}
