package com.lolens.modules.build;

import com.lolens.services.opgg.model.Position;

import java.util.List;

/**
 * 技能加点本地规则生成器。
 * <p>OP.GG MCP 未返回技能加点（skills）时的兜底方案：基于英雄定位标签（Data Dragon tags）
 * 生成 1-18 级 Q/W/E/R 加点序列，UI 展示时需标注"本地规则"。</p>
 */
public final class SkillOrderPlanner {

    private SkillOrderPlanner() {
    }

    /**
     * 生成 1-18 级技能加点序列。
     *
     * @param tags     英雄定位标签（Data Dragon champion.tags，如 MAGE/ASSASSIN/MARKSMAN 等）
     * @param position 召唤师位置（当前规则未细分，保留参数便于后续扩展）
     * @return 长度为 18 的列表，元素为 Q/W/E/R
     */
    public static List<String> plan(List<String> tags, Position position) {
        String primary = "Q";
        String secondary = "W";
        if (tags != null) {
            for (String tag : tags) {
                if ("assassin".equalsIgnoreCase(tag.trim())) {
                    secondary = "E";
                    break;
                }
            }
        }
        return build(primary, secondary);
    }

    /**
     * 生成标准模板：6/11/16 级加 R；1-5 级主技能 3 次；副技能在 2/8/10/12/13 级；
     * 第三技能在 4/14/15/17/18 级。
     */
    private static List<String> build(String primary, String secondary) {
        String tertiary = primary.equals("Q")
                ? (secondary.equals("W") ? "E" : "W")
                : (primary.equals("W")
                ? (secondary.equals("Q") ? "E" : "Q")
                : (secondary.equals("Q") ? "W" : "Q"));
        return List.of(
                primary, secondary, primary, tertiary, primary, "R",
                primary, secondary, primary, secondary, "R",
                secondary, secondary, tertiary, tertiary, "R",
                tertiary, tertiary
        );
    }
}
