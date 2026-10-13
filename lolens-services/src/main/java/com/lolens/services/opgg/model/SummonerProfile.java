package com.lolens.services.opgg.model;

/**
 * 召唤师资料（OP.GG MCP 返回的精简模型）。
 */
public record SummonerProfile(
        String gameName,
        String tagLine,
        int soloRankTier,       // 段位等级，如 20 = 钻石
        int soloRankDivision,   // 小段，1~4
        int soloLeaguePoints,   // 胜点
        double winRate,
        int totalGames,
        String iconUrl
) {

    /** 格式化段位展示。 */
    public String rankLabel() {
        // TODO: tier 数值编码需对齐 OP.GG MCP 实际返回（league_stats[].tier_info.tier），
        // 拿到真实样例后再补全映射；此处仅按原值展示，其余回退为数字。
        String tier = switch (soloRankTier) {
            case 20 -> "钻石";
            case 30 -> "大师";
            default -> "段位" + soloRankTier;
        };
        return tier + " " + soloRankDivision + " (" + soloLeaguePoints + " LP)";
    }
}
