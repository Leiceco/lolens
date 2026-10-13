package com.lolens.kernel.model;

import java.util.List;

/**
 * 对局内实时数据（端口 2999 /liveclientdata/allgamedata 精简模型）。
 */
public record LiveGameData(
        String gameId,
        String gameMode,
        double gameTimeSeconds,
        String mapName,
        List<LivePlayer> players,
        List<LiveEvent> events
) {

    /** 对局内的一名玩家。 */
    public record LivePlayer(
            String summonerName,
            String championName,
            int championId,
            int level,
            String team,
            String position,
            List<Item> items,
            SummonerSpells summonerSpells
    ) {
    }

    /** 装备条目。 */
    public record Item(int itemId, String displayName, int count) {
    }

    /** 召唤师技能。 */
    public record SummonerSpells(String summonerSpellOne, String summonerSpellTwo) {
    }

    /** 对局事件（击杀、推塔等）。 */
    public record LiveEvent(String eventType, double eventTime, String killerName) {
    }

    /** 便捷：按队伍过滤玩家。 */
    public List<LivePlayer> playersByTeam(String team) {
        return players.stream().filter(p -> team.equals(p.team())).toList();
    }
}
