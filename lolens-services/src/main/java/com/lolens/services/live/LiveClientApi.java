package com.lolens.services.live;

import com.lolens.kernel.model.LiveGameData;

import java.util.List;

/**
 * Live Client Data 客户端（对局内数据源，端口 2999）。
 * 由内核在 InProgress 阶段启动轮询（建议 5~10s 一次），发布 GameDataUpdated 事件。
 */
public interface LiveClientApi {

    LiveGameData getAllGameData();

    List<LiveGameData.Item> getPlayerItems(String summonerName);

    /** 本玩家是否已阵亡（读取 /liveclientdata/activeplayer 的 isDead / currentHealth）。 */
    boolean isPlayerDead();

    /** 端口 2999 是否可用。 */
    boolean isAvailable();
}
