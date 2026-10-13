package com.lolens.modules.build;

import com.lolens.services.opgg.model.ChampionAnalysis;
import com.lolens.services.opgg.model.Position;

import java.util.concurrent.CompletableFuture;

/**
 * 出装推荐服务：供模块内及未来其他模块查询英雄推荐出装。
 * 实现层默认委托 OP.GG，离线时返回本地兜底。
 */
public interface BuildRecommendationService {

    CompletableFuture<ChampionAnalysis> recommend(String championSlug, Position position);
}
