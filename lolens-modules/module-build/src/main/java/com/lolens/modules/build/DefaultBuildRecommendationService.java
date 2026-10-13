package com.lolens.modules.build;

import com.lolens.services.opgg.OpggClient;
import com.lolens.services.opgg.model.ChampionAnalysis;
import com.lolens.services.opgg.model.Position;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 默认出装推荐实现：委托 OP.GG 实时查询；OP.GG 服务不可用时返回本地通用推荐。
 */
public class DefaultBuildRecommendationService implements BuildRecommendationService {

    private final OpggClient opgg;

    public DefaultBuildRecommendationService() {
        this(null);
    }

    public DefaultBuildRecommendationService(OpggClient opgg) {
        this.opgg = opgg;
    }

    @Override
    public CompletableFuture<ChampionAnalysis> recommend(String championSlug, Position position) {
        if (opgg == null) {
            return CompletableFuture.completedFuture(localFallback(championSlug));
        }
        return opgg.getChampionAnalysis(championSlug, position);
    }

    /** 离线兜底：通用出门装推荐。 */
    public static ChampionAnalysis localFallback(String championSlug) {
        return new ChampionAnalysis(
                championSlug,
                Position.MIDDLE,
                List.of(
                        new ChampionAnalysis.RecommendedItem(1054, "多兰之刃", "core"),
                        new ChampionAnalysis.RecommendedItem(2003, "生命药水", "core"),
                        new ChampionAnalysis.RecommendedItem(3340, "侦查守卫", "core")
                ),
                // 名称使用英文（Precision=精密主系 / Press the Attack=强攻基石 / Domination=主宰副系），
                // 与 OP.GG 返回及 DataDragon/CDragon 名称映射对齐，保证一键配置天赋可识别。
                // 主系 4 个 + 副系 2 个名称齐备（小符文走 DefaultLcuPerksService 默认三件套），
                // 离线时一键配置天赋也可凑满 9 个槽位成功写入。
                List.of(new ChampionAnalysis.RecommendedRune(
                        "Precision", "Press the Attack", "Domination",
                        List.of("Taste of Blood", "Treasure Hunter"),
                        List.of("Press the Attack", "Triumph", "Legend: Alacrity", "Coup de Grace"),
                        null, List.of(), null, List.of(), List.of())),
                "离线模式：网络不可用，展示通用出门装，请以对局内情况为准",
                List.of()
        );
    }
}
