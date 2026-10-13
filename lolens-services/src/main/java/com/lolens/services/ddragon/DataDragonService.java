package com.lolens.services.ddragon;

import com.lolens.services.ddragon.model.Champion;
import com.lolens.services.ddragon.model.Item;

import java.util.Optional;

/**
 * Data Dragon 服务：英雄数据、装备数据、图标 URL。启动时预加载到本地缓存，避免选人阶段卡顿。
 */
public interface DataDragonService {

    /** 当前版本。 */
    String version();

    Optional<Champion> champion(int id);

    Optional<Champion> championBySlug(String slug);

    /** 按装备 ID 查询装备（名称、价格）；未知装备返回 empty。 */
    Optional<Item> item(int itemId);

    /** 按名称查询装备（item.json 名称匹配，忽略大小写/空格/撇号）；未知返回 empty。 */
    Optional<Item> itemByName(String name);

    String championIconUrl(int championId);

    String itemIconUrl(int itemId);

    /** 符文图标 URL（基石/副符文等单个符文，按符文名匹配 CDragon perks.json）；未匹配返回空串。 */
    String runeIconUrl(String runeName);

    /** 符文页面图标 URL（主系/副系页面，按页面名匹配 CDragon perkstyles.json）；未匹配返回空串。 */
    String runePageIconUrl(String pageName);

    /** 符文 id（perks.json 的 perk.id），按符文名匹配（忽略大小写/空格/撇号）；未匹配返回 empty。 */
    Optional<Integer> runeIdByName(String runeName);

    /** 符文页面 id（perkstyles.json 的 style.id，即 LCU style id），按页面名匹配；未匹配返回 empty。 */
    Optional<Integer> runePageIdByName(String pageName);

    /** 英雄技能图标 URL：abilityIndex 0=Q 1=W 2=E 3=R；英雄未知或索引越界返回空串。 */
    String spellIconUrl(String championSlug, int abilityIndex);

    /**
     * 召唤师技能图标 URL（如闪现/点燃/传送），按 LCU/Data Dragon summoner spell id 映射；
     * 未知 id 返回空串。
     */
    String summonerSpellIconUrl(int summonerSpellId);
}
