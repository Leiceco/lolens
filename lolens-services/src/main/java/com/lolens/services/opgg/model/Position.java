package com.lolens.services.opgg.model;

/**
 * 召唤师对局位置枚举。
 */
public enum Position {
    TOP("top"),
    JUNGLE("jungle"),
    MIDDLE("middle"),
    BOTTOM("bottom"),
    UTILITY("utility"),
    /** 位置未知/未分配时使用：OP.GG 全位置综合数据。 */
    ALL("all");

    private final String slug;

    Position(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    /** 从位置标识解析；未知或空返回 null，避免静默映射到错误位置。 */
    public static Position fromSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return switch (slug.trim().toLowerCase()) {
            case "top" -> TOP;
            case "jungle", "jg" -> JUNGLE;
            case "middle", "mid" -> MIDDLE;
            case "bottom", "bot", "adc", "marksman" -> BOTTOM;
            case "utility", "support", "sup" -> UTILITY;
            default -> null;
        };
    }
}
