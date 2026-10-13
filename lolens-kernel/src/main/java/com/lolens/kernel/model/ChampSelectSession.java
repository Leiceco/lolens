package com.lolens.kernel.model;

import java.util.List;

/**
 * 选人阶段会话数据（LCU /lol-champ-select/v1/session 精简模型）。
 */
public record ChampSelectSession(
        long sessionId,
        List<ChampSelectPlayer> myTeam,
        List<ChampSelectPlayer> theirTeam,
        Integer myChampionId,
        String myPosition
) {

    /** 选人阶段中的一名玩家。 */
    public record ChampSelectPlayer(
            String summonerName,
            Integer championId,
            Integer championPickIntent,
            String assignedPosition,
            String team
    ) {
    }
}
