package com.lolens.app;

import com.lolens.modules.autoreply.AutoReplyModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 应用配置：读取 classpath 下 config/app.properties，为数据层服务与数据采集提供可配置项。
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final String RESOURCE = "/config/app.properties";

    private final Properties props = new Properties();

    public AppConfig() {
        try (InputStream in = AppConfig.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                // properties 文件含中文（自动回复规则等），必须按 UTF-8 解码；
                // Properties.load(InputStream) 按 ISO-8859-1 解码会导致中文触发词全部乱码失效
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                log.warn("未找到配置文件 {}，使用默认值", RESOURCE);
            }
        } catch (IOException e) {
            log.warn("读取配置失败 {}: {}", RESOURCE, e.getMessage());
        }
    }

    public String lockfilePath() {
        return props.getProperty("lcu.lockfile.path", "C:/Riot Games/League of Legends/lockfile");
    }

    public String opggMcpUrl() {
        return props.getProperty("opgg.mcp.url", "https://mcp-api.op.gg/mcp");
    }

    /** OP.GG MCP 查询区域（召唤师战绩查询用）。 */
    public String opggRegion() {
        return props.getProperty("opgg.region", "kr");
    }

    public double overlayOpacity() {
        return parseDouble("overlay.opacity", 0.92);
    }

    public int sendCooldownSeconds() {
        return parseInt("send.cooldown.seconds", 5);
    }

    public int livePollIntervalSeconds() {
        return parseInt("live.poll.interval.seconds", 5);
    }

    public int autoAcceptPollIntervalSeconds() {
        return parseInt("autoaccept.poll.interval.seconds", 1);
    }

    /** 自动接受对局：检测到 ReadyCheck 后延迟执行的秒数（0 表示立即接受）。 */
    public int autoAcceptDelaySeconds() {
        return parseInt("autoaccept.delay.seconds", 0);
    }

    /** 自动点赞：结算阶段轮询 ballot 的间隔（秒）。 */
    public int honorPollIntervalSeconds() {
        return parseInt("honor.poll.interval.seconds", 3);
    }

    /** 自动点赞：仅点赞队友（true）或包含敌方（false）。 */
    public boolean honorAlliesOnly() {
        return Boolean.parseBoolean(props.getProperty("honor.allies.only", "true"));
    }

    /** 自动点赞：荣誉类型（MVP / HEART / COOL）。 */
    public String honorType() {
        return props.getProperty("honor.type", "MVP");
    }

    /** 自动回复：轮询会话新消息的间隔（秒）。 */
    public int autoReplyPollIntervalSeconds() {
        return parseInt("autoreply.poll.interval.seconds", 2);
    }

    /** 自动回复：规则列表（格式 trigger=response，多个规则用 | 分隔）。 */
    public List<AutoReplyModule.ReplyRule> autoReplyRules() {
        String raw = props.getProperty("autoreply.rules", "");
        List<AutoReplyModule.ReplyRule> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String rule : raw.split("\\|")) {
            int eq = rule.indexOf('=');
            if (eq <= 0 || eq == rule.length() - 1) {
                log.warn("自动回复规则格式非法，已忽略: {}", rule);
                continue;
            }
            result.add(new AutoReplyModule.ReplyRule(rule.substring(0, eq).trim(), rule.substring(eq + 1).trim()));
        }
        return result;
    }

    /** 返回房间/重赛：轮询 gameflow-phase 的间隔（秒）。 */
    public int lobbyFlowPollIntervalSeconds() {
        return parseInt("lobbyflow.poll.interval.seconds", 2);
    }

    /** 返回房间/重赛：结算后自动 play-again 的延迟（秒）。 */
    public int playAgainDelaySeconds() {
        return parseInt("lobbyflow.playagain.delay.seconds", 5);
    }

    /** 返回房间/重赛：Reconnect 后自动重连的延迟（秒）。 */
    public int reconnectDelaySeconds() {
        return parseInt("lobbyflow.reconnect.delay.seconds", 10);
    }

    /** 在线状态自定义：availability（chat / mobile / away）。 */
    public String chatStatusAvailability() {
        return props.getProperty("chatstatus.availability", "chat");
    }

    /** 在线状态自定义：个性签名（statusMessage）。 */
    public String chatStatusMessage() {
        return props.getProperty("chatstatus.statusMessage", "");
    }

    /** 在线状态自定义：展示的排位队列（如 RANKED_SOLO_5x5）。 */
    public String chatStatusRankedQueue() {
        return props.getProperty("chatstatus.ranked.queue", "");
    }

    /** 在线状态自定义：展示的段位（如 DIAMOND / GOLD）。 */
    public String chatStatusRankedTier() {
        return props.getProperty("chatstatus.ranked.tier", "");
    }

    /** 在线状态自定义：展示的段位分档（I / II / III / IV）。 */
    public String chatStatusRankedDivision() {
        return props.getProperty("chatstatus.ranked.division", "");
    }

    public int respawnPollIntervalSeconds() {
        return parseInt("respawn.poll.interval.seconds", 1);
    }

    /** 战绩查询：每次查询返回的最近对局场数。 */
    public int matchHistoryQueryCount() {
        return parseInt("matchhistory.query.count", 20);
    }

    /** 自动选人/禁用：选人阶段轮询间隔（秒）。 */
    public int pickBanPollIntervalSeconds() {
        return parseInt("pickban.poll.interval.seconds", 2);
    }

    /** 预设自动选择英雄（Data Dragon championId 列表，逗号分隔）。 */
    public List<Integer> pickBanPickChampionIds() {
        return parseIdList("pickban.pick.championIds");
    }

    /** 预设自动禁用英雄（Data Dragon championId 列表，逗号分隔）。 */
    public List<Integer> pickBanBanChampionIds() {
        return parseIdList("pickban.ban.championIds");
    }

    private List<Integer> parseIdList(String key) {
        String raw = props.getProperty(key, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                log.warn("配置项 {} 包含非法值 {}，已忽略", key, part);
            }
        }
        return result;
    }

    private int parseInt(String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 非法，使用默认 {}: {}", key, fallback, props.getProperty(key));
            return fallback;
        }
    }

    private double parseDouble(String key, double fallback) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 非法，使用默认 {}: {}", key, fallback, props.getProperty(key));
            return fallback;
        }
    }
}
