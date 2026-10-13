package com.lolens.modules.skill;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能 CD 追踪器：记录召唤师技能 / 主动技能的施放时刻，计算剩余冷却。
 *
 * <p>对局内由玩家通过热键（Alt+1~5 记录、Alt+Q 发送摘要）标记施放，
 * 面板每秒刷新剩余时间。基于游戏内时钟（Live Client Data gameTime）计算，
 * 避免游戏暂停 / 切屏时 wall-clock 继续走导致的误差。仅供玩家辅助决策，不读取游戏内存。</p>
 */
public class SkillTracker {

    /** 施放槽位 → 施放时的游戏时间（秒）。 */
    private final Map<String, Double> lastCastAtGameTime = new ConcurrentHashMap<>();
    private volatile double lastGameTimeSeconds = 0;

    /** 召唤师技能基础冷却表（秒）。 */
    public static final Map<String, Integer> SUMMONER_SPELL_CD = Map.ofEntries(
            Map.entry("SummonerFlash", 300),
            Map.entry("SummonerTeleport", 360),
            Map.entry("SummonerDot", 180),
            Map.entry("SummonerHeal", 240),
            Map.entry("SummonerBarrier", 180),
            Map.entry("SummonerExhaust", 210),
            Map.entry("SummonerHaste", 210),
            Map.entry("SummonerBoost", 210),
            Map.entry("SummonerSmite", 15)
    );

    /** 新对局开始：清空记录。 */
    public void reset() {
        lastCastAtGameTime.clear();
    }

    /** 记录槽位技能被施放（热键触发），以最近一次轮询到的游戏时间近似标记。 */
    public void recordCast(String slot) {
        // 首个 GameDataUpdated 尚未到达（gameTime=0）时忽略记录，避免 CD 起点被记为 0
        double gameTime = lastGameTimeSeconds;
        if (gameTime > 0) {
            lastCastAtGameTime.put(slot, gameTime);
        }
    }

    public boolean wasCast(String slot) {
        return lastCastAtGameTime.containsKey(slot);
    }

    /** 剩余冷却秒数；未施放过返回 0（视为可用）。向上取整，未满 1 秒显示整秒。 */
    public long remainingSeconds(String slot, int cooldownSeconds) {
        Double castAt = lastCastAtGameTime.get(slot);
        if (castAt == null) {
            return 0;
        }
        double elapsed = lastGameTimeSeconds - castAt;
        return Math.max(0, (long) Math.ceil(cooldownSeconds - elapsed));
    }

    public int cooldownOf(String spellKey) {
        return SUMMONER_SPELL_CD.getOrDefault(spellKey, 0);
    }

    /**
     * 更新当前游戏时间（来自 Live Client Data 轮询）。
     * 事件总线异步乱序时，上一局的数据可能晚于新一局的 reset 到达；
     * 若游戏时间大幅回退（>5s，同一局内游戏时间单调递增），视为进入新对局并清空施放记录。
     */
    public void setGameTime(double gameTimeSeconds) {
        double prev = lastGameTimeSeconds;
        if (prev > 0 && gameTimeSeconds >= 0 && gameTimeSeconds < prev - 5) {
            reset();
        }
        this.lastGameTimeSeconds = gameTimeSeconds;
    }
}
