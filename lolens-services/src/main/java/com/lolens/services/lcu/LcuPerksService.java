package com.lolens.services.lcu;

import com.lolens.services.opgg.model.ChampionAnalysis;

/**
 * 一键配置天赋：把 OP.GG 推荐符文写入客户端符文页并设为当前使用。
 *
 * <p>合规边界：仅调用 LCU 本地 API（/lol-perks/v1/），不读写游戏内存。</p>
 */
public interface LcuPerksService {

    /**
     * 将推荐符文写入客户端符文页并设为当前使用。同名页存在时更新复用，避免重复创建。
     * 全程不抛异常，失败原因封装在 {@link ApplyResult#message()}。
     *
     * @param pageName 符文页名称（建议含英雄名以便识别）
     * @param rune     推荐符文模型（优先使用 OP.GG 返回的 id，缺失时按名称经 Data Dragon 映射兜底）
     */
    ApplyResult apply(String pageName, ChampionAnalysis.RecommendedRune rune);

    /** LCU 是否已连接。 */
    boolean isConnected();

    /** 当前游戏阶段是否为选人阶段（ChampionSelect）。 */
    boolean isChampionSelect();

    /** 写入结果。 */
    record ApplyResult(boolean success, String pageName, Integer pageId, String message) {
    }
}
