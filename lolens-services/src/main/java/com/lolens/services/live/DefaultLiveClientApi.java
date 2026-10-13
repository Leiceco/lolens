package com.lolens.services.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lolens.kernel.model.LiveGameData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Live Client Data 默认实现：请求 127.0.0.1:2999 的 HTTP 端点。
 * 端口 2999 不验证 HTTPS 证书，失败时静默降级（isAvailable=false）。
 */
public class DefaultLiveClientApi implements LiveClientApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultLiveClientApi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "https://127.0.0.1:2999";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public LiveGameData getAllGameData() {
        try {
            JsonNode root = getJson("/liveclientdata/allgamedata");
            return parse(root);
        } catch (Exception e) {
            log.debug("Live Client Data 不可用: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<LiveGameData.Item> getPlayerItems(String summonerName) {
        try {
            JsonNode items = getJson("/liveclientdata/playeritems?summonerName="
                    + URLEncoder.encode(summonerName, StandardCharsets.UTF_8));
            List<LiveGameData.Item> result = new ArrayList<>();
            for (JsonNode node : items) {
                result.add(new LiveGameData.Item(
                        node.path("itemID").asInt(),
                        node.path("displayName").asText(),
                        node.path("count").asInt()
                ));
            }
            return result;
        } catch (Exception e) {
            log.debug("读取玩家装备失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isPlayerDead() {
        try {
            JsonNode active = getJson("/liveclientdata/activeplayer");
            if (active == null) {
                return false;
            }
            // activeplayer 端点结构：{abilities, championStats{currentHealth,...}, currentGold,
            // fullRunes, level, summonerName}。它没有顶层 isDead；currentHealth 位于
            // championStats 之下（原实现读顶层 currentHealth 恒取默认值 1.0，导致恒判存活）。
            if (active.has("isDead")) {
                return active.path("isDead").asBoolean(false);
            }
            return active.path("championStats").path("currentHealth").asDouble(1.0) <= 0;
        } catch (Exception e) {
            log.debug("读取本玩家状态失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return getJson("/liveclientdata/gamestats") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    private LiveGameData parse(JsonNode root) {
        List<LiveGameData.LivePlayer> players = new ArrayList<>();
        for (JsonNode p : root.path("allPlayers")) {
            players.add(new LiveGameData.LivePlayer(
                    p.path("summonerName").asText(),
                    p.path("championName").asText(),
                    p.path("championId").asInt(),
                    p.path("level").asInt(),
                    p.path("team").asText(),
                    p.path("position").asText(),
                    parseItems(p.path("items")),
                    new LiveGameData.SummonerSpells(
                            summonerSpellKey(p.path("summonerSpells"), "summonerSpellOne"),
                            summonerSpellKey(p.path("summonerSpells"), "summonerSpellTwo")
                    )
            ));
        }
        return new LiveGameData(
                root.path("gameData").path("gameId").asText(),
                root.path("gameData").path("gameMode").asText(),
                root.path("gameData").path("gameTime").asDouble(),
                root.path("gameData").path("mapName").asText(),
                players,
                List.of()
        );
    }

    /**
     * 召唤师技能键名：优先取 rawDisplayName（如 "SummonerFlash"，与技能 CD 表键一致），
     * 缺失时回退 displayName（本地化显示名）。原实现只取 displayName，导致 CD 查表恒 miss。
     */
    private static String summonerSpellKey(JsonNode spells, String field) {
        JsonNode spell = spells.path(field);
        String raw = spell.path("rawDisplayName").asText("");
        return raw.isBlank() ? spell.path("displayName").asText("") : raw;
    }

    private List<LiveGameData.Item> parseItems(JsonNode itemsNode) {
        List<LiveGameData.Item> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            items.add(new LiveGameData.Item(
                    item.path("itemID").asInt(),
                    item.path("displayName").asText(),
                    item.path("count").asInt()
            ));
        }
        return items;
    }
}
