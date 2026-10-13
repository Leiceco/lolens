package com.lolens.services.opgg;

import com.lolens.services.opgg.model.ChampionAnalysis;
import com.lolens.services.opgg.model.Position;
import com.lolens.services.opgg.model.SummonerProfile;

import java.util.concurrent.CompletableFuture;

/**
 * OP.GG MCP 客户端：封装 https://mcp-api.op.gg/mcp 调用。
 * 使用 Caffeine 缓存（TTL 30 分钟），失败重试 1 次，仍失败由 UI 降级展示。
 */
public interface OpggClient {

    CompletableFuture<ChampionAnalysis> getChampionAnalysis(String championSlug, Position position);

    CompletableFuture<SummonerProfile> getSummonerProfile(String gameName, String tagLine);
}
