package com.lolens.services.opgg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lolens.services.cache.AppCache;
import com.lolens.services.opgg.model.ChampionAnalysis;
import com.lolens.services.opgg.model.Position;
import com.lolens.services.opgg.model.SummonerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OP.GG MCP 客户端默认实现。
 * <p>按 MCP 标准协议调用（tools/call + 工具名 + arguments），解析远端返回的
 * 类 repr 文本（LolGetChampionAnalysis("LUX","MID",Data(CoreItems(...),...))），
 * 映射到 {@link ChampionAnalysis} 供出装/符文/技能加点展示。</p>
 * <p>远端不可用或解析失败时返回默认降级数据，绝不阻塞其他面板。</p>
 */
public class DefaultOpggClient implements OpggClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultOpggClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_MCP_URL = "https://mcp-api.op.gg/mcp";
    /** 缓存键版本：OP.GG 协议升级导致模型变化时递增，避免旧 JSON 污染新模型。 */
    private static final String CACHE_VERSION = "v2";
    /** MCP 请求 id 自增（协议允许任意整数，避免所有请求共用 id=1）。 */
    private static final AtomicInteger MCP_REQUEST_ID = new AtomicInteger(1);

    private final String mcpUrl;
    private final String region;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AppCache cache = AppCache.instance();

    /** 专用线程池，避免阻塞 ForkJoinPool.commonPool（重试中含 sleep）。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "opgg-client");
        t.setDaemon(true);
        return t;
    });

    /** 在途请求去重（single-flight）：同一 key 的并发调用共享同一 future，避免事件风暴重复打 OP.GG。 */
    private final Map<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();

    public DefaultOpggClient() {
        this(DEFAULT_MCP_URL);
    }

    public DefaultOpggClient(String mcpUrl) {
        this(mcpUrl, "kr");
    }

    public DefaultOpggClient(String mcpUrl, String region) {
        this.mcpUrl = (mcpUrl == null || mcpUrl.isBlank()) ? DEFAULT_MCP_URL : mcpUrl;
        this.region = (region == null || region.isBlank()) ? "kr" : region;
    }

    @Override
    public CompletableFuture<ChampionAnalysis> getChampionAnalysis(String championSlug, Position position) {
        String key = "analysis:" + CACHE_VERSION + ":" + championSlug + ":" + position.slug();
        ChampionAnalysis cached = cache.get(key, ChampionAnalysis.class);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        // 出装推荐有本地兜底，失败不做负缓存（网络恢复后可立即重试）
        return fetchCached(key, () -> doFetchAnalysis(championSlug, position), false);
    }

    @Override
    public CompletableFuture<SummonerProfile> getSummonerProfile(String gameName, String tagLine) {
        String key = "profile:" + CACHE_VERSION + ":" + gameName + ":" + tagLine;
        // 失败负缓存：TTL 内直接快速失败，避免选人阶段每 2s 对同一批召唤师重复发起请求
        if (cache.get(key + ":err", String.class) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("SummonerProfile 查询失败（命中失败缓存）"));
        }
        SummonerProfile cached = cache.get(key, SummonerProfile.class);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return fetchCached(key, () -> doFetchProfile(gameName, tagLine), true);
    }

    /** 带缓存与在途去重的取数：成功写缓存；negCache=true 时失败写负缓存（TTL 内不再重试）。 */
    private <T> CompletableFuture<T> fetchCached(String key, ThrowingSupplier<T> supplier, boolean negCache) {
        @SuppressWarnings("unchecked")
        CompletableFuture<T> existing = (CompletableFuture<T>) inflight.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<T> raced = (CompletableFuture<T>) inflight.putIfAbsent(key, future);
        if (raced != null) {
            return raced;
        }
        fetchWithRetry(supplier).whenComplete((value, ex) -> {
            inflight.remove(key);
            if (ex != null) {
                if (negCache) {
                    cache.put(key + ":err", "1");
                }
                Throwable cause = (ex instanceof CompletionException ce && ce.getCause() != null)
                        ? ce.getCause() : ex;
                future.completeExceptionally(cause);
            } else {
                cache.put(key, value);
                future.complete(value);
            }
        });
        return future;
    }

    /** 允许抛出受检异常的 Supplier。 */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> CompletableFuture<T> fetchWithRetry(ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception first) {
                log.warn("OP.GG 首次请求失败，重试一次: {}", first.getMessage());
                try {
                    Thread.sleep(500);
                    return supplier.get();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
        }, executor);
    }

    // ---------------------------------------------------------------------
    // 出装 / 符文 / 技能加点
    // ---------------------------------------------------------------------

    private ChampionAnalysis doFetchAnalysis(String championSlug, Position position) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("game_mode", "ranked");
        args.put("champion", toOpggChampion(championSlug));
        args.put("position", toOpggPosition(position));
        args.put("lang", "en_US");
        args.put("desired_output_fields", List.of(
                "champion",
                "position",
                "data.starter_items.{ids[],ids_names[],pick_rate,play,win}",
                "data.core_items.{ids[],ids_names[],pick_rate,play,win}",
                "data.boots.{ids[],ids_names[],pick_rate,play,win}",
                "data.skills.{order[],pick_rate,play,win}",
                "data.runes.{id,pick_rate,play,primary_page_id,primary_page_name,primary_rune_ids[],primary_rune_names[],secondary_page_id,secondary_page_name,secondary_rune_ids[],secondary_rune_names[],stat_mod_ids[],stat_mod_names[],win}"
        ));
        String text = callMcpText("lol_get_champion_analysis", args);

        List<ChampionAnalysis.RecommendedItem> items = new ArrayList<>();
        List<ChampionAnalysis.RecommendedRune> runes = new ArrayList<>();
        List<String> skills = List.of();

        // 服务端 repr 无字段名，Data(...) 内部按固定顺序输出顶层类块：
        // Data(CoreItems(core), CoreItems(boots), CoreItems(starter), Runes(...), Skills(...))
        // 实测（2026-10）三个 CoreItems 依次为核心三件套、鞋子、出门装；无 fourth/fifth/sixth 字段。
        String dataBlock = blockAfter(text, "Data(");
        if (dataBlock != null) {
            int coreCount = 0;
            for (String token : splitTopLevel(innerOf(dataBlock))) {
                String t = token.trim();
                if (t.startsWith("CoreItems(")) {
                    String tier = coreCount == 0 ? "core" : (coreCount == 1 ? "boots" : "starter");
                    parseItemsToken(t, tier, items);
                    coreCount++;
                } else if (t.startsWith("Runes(")) {
                    runes.addAll(parseRunesToken(t));
                } else if (t.startsWith("Skills(")) {
                    skills = parseSkillsToken(t);
                }
            }
        } else {
            log.warn("OP.GG 响应缺少 Data 数据块: {}", abbreviate(text));
        }

        skills = normalizeSkillOrder(skills);
        return new ChampionAnalysis(championSlug, position, items, runes, "", skills);
    }

    /** 解析 CoreItems 块：CoreItems([ids],[names],play,win,pick_rate)。 */
    private void parseItemsToken(String token, String tier, List<ChampionAnalysis.RecommendedItem> out) throws Exception {
        List<String> tokens = splitTopLevel(argsOf(token));
        if (tokens.size() < 2) {
            return;
        }
        JsonNode ids = MAPPER.readTree(tokens.get(0));
        JsonNode names = MAPPER.readTree(tokens.get(1));
        int n = Math.min(ids.size(), names.size());
        for (int i = 0; i < n; i++) {
            out.add(new ChampionAnalysis.RecommendedItem(
                    ids.get(i).asInt(), names.get(i).asText(), tier));
        }
    }

    /** 解析 Runes 块：Runes(id,primary_page_id,"Page",[runeIds],[runeNames],secondaryPageId,"SecPage",[secIds],[secNames],[statIds],[statNames],play,win,pick)。 */
    private List<ChampionAnalysis.RecommendedRune> parseRunesToken(String token) throws Exception {
        List<ChampionAnalysis.RecommendedRune> result = new ArrayList<>();
        // 注意：此处必须用 argsOf（提取括号平衡的参数区），不能用 innerOf（会误删 token 首字符 'R' 导致括号失衡、符文解析恒为空）
        List<String> tokens = splitTopLevel(argsOf(token));
        // tokens: 0=id 1=primary_page_id 2=primary_page_name 3=primary_rune_ids
        //        4=primary_rune_names 5=secondary_page_id 6=secondary_page_name
        //        7=secondary_rune_ids 8=secondary_rune_names 9=stat_mod_ids 10=stat_mod_names
        if (tokens.size() < 9) {
            return result;
        }
        String page = quotedValue(tokens.get(2));
        Integer primaryStyleId = null;
        try {
            primaryStyleId = Integer.parseInt(tokens.get(1).trim());
        } catch (Exception ignored) {
            // 页面 id 缺失不阻断展示
        }
        List<Integer> primaryRuneIds = new ArrayList<>();
        List<String> primaryRunes = new ArrayList<>();
        String keystone = "";
        try {
            JsonNode ids = MAPPER.readTree(tokens.get(3));
            for (JsonNode n : ids) {
                primaryRuneIds.add(n.asInt());
            }
            JsonNode names = MAPPER.readTree(tokens.get(4));
            for (JsonNode n : names) {
                primaryRunes.add(n.asText());
            }
            if (!primaryRunes.isEmpty()) {
                keystone = primaryRunes.get(0);
            }
        } catch (Exception ignored) {
            // 基石缺失不阻断符文页展示
        }
        String secondaryPage = quotedValue(tokens.get(6));
        Integer secondaryStyleId = null;
        try {
            secondaryStyleId = Integer.parseInt(tokens.get(5).trim());
        } catch (Exception ignored) {
            // 副页 id 缺失不阻断
        }
        List<Integer> secondaryRuneIds = new ArrayList<>();
        List<String> secondaryRunes = new ArrayList<>();
        try {
            JsonNode ids = MAPPER.readTree(tokens.get(7));
            for (JsonNode n : ids) {
                secondaryRuneIds.add(n.asInt());
            }
            JsonNode names = MAPPER.readTree(tokens.get(8));
            for (JsonNode n : names) {
                secondaryRunes.add(n.asText());
            }
        } catch (Exception ignored) {
            // 副页缺失不阻断
        }
        List<Integer> statModIds = new ArrayList<>();
        try {
            if (tokens.size() > 9) {
                JsonNode statIds = MAPPER.readTree(tokens.get(9));
                for (JsonNode n : statIds) {
                    statModIds.add(n.asInt());
                }
            }
        } catch (Exception ignored) {
            // 小符文缺失不阻断
        }
        result.add(new ChampionAnalysis.RecommendedRune(
                page, keystone, secondaryPage, secondaryRunes,
                primaryRunes, primaryStyleId, primaryRuneIds,
                secondaryStyleId, secondaryRuneIds, statModIds));
        return result;
    }

    /** 解析 Skills 块：Skills([order],play,win,pick)。返回 1-18 级序列。 */
    private List<String> parseSkillsToken(String token) throws Exception {
        List<String> tokens = splitTopLevel(argsOf(token));
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<String> order = new ArrayList<>();
        JsonNode arr = MAPPER.readTree(tokens.get(0));
        for (JsonNode n : arr) {
            String s = n.asText("").trim();
            if (!s.isBlank()) {
                order.add(s.toUpperCase());
            }
        }
        return order;
    }

    /** OP.GG 通常只返回前 15 级加点，补全到 18 级：6/11/16 级补 R，其余补点数未满 5 的基础技能。 */
    private static List<String> normalizeSkillOrder(List<String> order) {
        if (order == null || order.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(order);
        if (result.size() < 18) {
            Map<String, Integer> counts = new HashMap<>();
            for (String s : result) {
                counts.merge(s.toUpperCase(), 1, Integer::sum);
            }
            String primary = result.get(0).toUpperCase();
            while (result.size() < 18) {
                int nextLevel = result.size() + 1;
                String add;
                if (nextLevel == 6 || nextLevel == 11 || nextLevel == 16) {
                    add = "R";
                } else if (counts.getOrDefault(primary, 0) >= 5) {
                    // 主技能已满 5 点：改补点数最少的基础技能，避免生成 7 点 Q 的非法序列
                    add = underfilledSkill(counts, primary);
                } else {
                    add = primary;
                }
                result.add(add);
                counts.merge(add, 1, Integer::sum);
            }
        }
        if (result.size() > 18) {
            result = new ArrayList<>(result.subList(0, 18));
        }
        return result;
    }

    /** 在 Q/W/E 中选一个未满 5 点且当前点数最少的技能。 */
    private static String underfilledSkill(Map<String, Integer> counts, String primary) {
        String best = primary;
        int bestCount = Integer.MAX_VALUE;
        for (String s : new String[]{"Q", "W", "E"}) {
            int c = counts.getOrDefault(s, 0);
            if (c < 5 && c < bestCount) {
                best = s;
                bestCount = c;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------
    // 战绩（保持既有失败降级语义：工具名修正为真实协议，解析不支持时抛出）
    // ---------------------------------------------------------------------

    private SummonerProfile doFetchProfile(String gameName, String tagLine) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("game_name", gameName);
        args.put("tag_line", tagLine);
        args.put("region", region);
        args.put("desired_output_fields", List.of(
                "data.summoner.{id,name,tagline,level}",
                "data.summoner.rank_stats.{tier,division,lp,win_rate,total_games}"));
        String text = callMcpText("lol_get_summoner_profile", args);
        SummonerProfile profile = parseProfileRepr(text);
        if (profile == null) {
            // 保留失败语义（上层据此降级展示），并带原始文本便于后续补充解析规则
            throw new IllegalStateException("SummonerProfile 解析失败: " + abbreviate(text));
        }
        return profile;
    }

    /**
     * 尽力解析 OP.GG MCP 的 profile repr 文本：Summoner(...) 与 RankStats(...) 块。
     * 块内按 desired_output_fields 声明顺序取值（id,name,tagline,level /
     * tier,division,lp,win_rate,total_games），支持裸值与 "key=value" 形式；
     * 两个块都缺失或内容全空时返回 null。格式以实测为准，解析失败不影响其他面板。
     */
    private static SummonerProfile parseProfileRepr(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String gameName = "";
        String tagLine = "";
        int level = 0;
        int tier = 0;
        int division = 0;
        int lp = 0;
        int totalGames = 0;
        double winRate = 0;
        boolean found = false;

        String sumBlock = blockAfter(text, "Summoner(");
        if (sumBlock != null) {
            List<String> parts = splitTopLevel(innerOf(sumBlock));
            // 字段顺序:id,name,tagline,level
            if (parts.size() >= 2) {
                gameName = unquoted(parts.get(1));
            }
            if (parts.size() >= 3) {
                tagLine = unquoted(parts.get(2));
            }
            if (parts.size() >= 4) {
                level = parseIntSafe(parts.get(3));
            }
            found = true;
        }

        String rankBlock = blockAfter(text, "RankStats(");
        if (rankBlock != null) {
            List<String> parts = splitTopLevel(innerOf(rankBlock));
            // 字段顺序:tier,division,lp,win_rate,total_games
            if (parts.size() >= 1) {
                tier = parseIntSafe(parts.get(0));
            }
            if (parts.size() >= 2) {
                division = parseIntSafe(parts.get(1));
            }
            if (parts.size() >= 3) {
                lp = parseIntSafe(parts.get(2));
            }
            if (parts.size() >= 4) {
                winRate = parseDoubleSafe(parts.get(3));
            }
            if (parts.size() >= 5) {
                totalGames = parseIntSafe(parts.get(4));
            }
            found = true;
        }
        if (!found || (gameName.isBlank() && tagLine.isBlank() && level == 0
                && tier == 0 && division == 0 && lp == 0 && winRate == 0 && totalGames == 0)) {
            return null;
        }
        return new SummonerProfile(gameName, tagLine, tier, division, lp, winRate, totalGames, "");
    }

    /** 去掉引号，并兼容 "key=value" 形式的 token。 */
    private static String unquoted(String token) {
        String t = token.trim();
        int eq = t.indexOf('=');
        if (eq >= 0) {
            t = t.substring(eq + 1).trim();
        }
        return quotedValue(t);
    }

    private static int parseIntSafe(String token) {
        try {
            return Integer.parseInt(unquoted(token));
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDoubleSafe(String token) {
        try {
            return Double.parseDouble(unquoted(token));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 120 ? s.substring(0, 120) + "..." : s);
    }

    // ---------------------------------------------------------------------
    // MCP 协议与 repr 文本解析基础
    // ---------------------------------------------------------------------

    /**
     * 按 MCP 标准协议调用工具并返回 content[0].text。
     * 工具级错误（-32601 Method not found 等）与业务错误（isError）统一抛异常走降级。
     */
    private String callMcpText(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("jsonrpc", "2.0");
        call.put("method", "tools/call");
        call.put("params", Map.of("name", toolName, "arguments", arguments));
        call.put("id", MCP_REQUEST_ID.incrementAndGet());

        // 实测 OP.GG MCP 单次请求耗时约 23~30s（含 schema 响应与真实数据），20s 会大概率超时降级，
        // 导致出装/符文/技能走本地兜底；放宽到 40s 并配合重试（整体最坏约 80s，但成功率显著提升）。
        HttpRequest request = HttpRequest.newBuilder(URI.create(mcpUrl))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(call)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        if (root.has("error") && !root.get("error").isNull()) {
            throw new IllegalStateException("MCP error: " + root.get("error"));
        }
        JsonNode result = root.path("result");
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException("MCP tool error: " + abbreviate(result.toString()));
        }
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            String text = content.get(0).path("text").asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        throw new IllegalStateException("MCP 返回空内容: " + abbreviate(result.toString()));
    }

    /** 英雄名转 OP.GG UPPER_SNAKE_CASE：Lux->LUX，MonkeyKing->MONKEY_KING。 */
    private static String toOpggChampion(String slug) {
        String s = slug == null ? "" : slug.trim();
        if (s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * 内部位置枚举映射为 OP.GG position 枚举（top/jungle/mid/adc/support）。
     * 实测（2026-10）OP.GG MCP 拒绝 position="all"/"flex"/大写，仅接受小写有效位置且必填；
     * 因此 ALL/未知位置统一兜底为 "mid"（服务端支持的综合位置），避免协议错误导致整条链路降级。
     */
    private static String toOpggPosition(Position position) {
        if (position == null) {
            return "mid";
        }
        return switch (position) {
            case TOP -> "top";
            case JUNGLE -> "jungle";
            case MIDDLE -> "mid";
            case BOTTOM -> "adc";
            case UTILITY -> "support";
            case ALL -> "mid";
        };
    }

    /** 从文本定位 marker 起始的括号平衡块（含括号）。marker 形如 "Data("（以 '(' 结尾即块起点）。 */
    private static String blockAfter(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        final int start;
        final char open;
        final char close;
        if (marker.endsWith("(")) {
            start = idx + marker.length() - 1;
            open = '(';
            close = ')';
        } else {
            int s = idx + marker.length();
            while (s < text.length() && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            int found = -1;
            for (int i = s; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            start = found;
            open = text.charAt(found);
            close = open == '(' ? ')' : (open == '[' ? ']' : '}');
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 去掉外层括号/方括号。 */
    private static String innerOf(String block) {
        if (block == null || block.length() < 2) {
            return block == null ? "" : block;
        }
        return block.substring(1, block.length() - 1);
    }

    /** 提取类调用 token（如 CoreItems(...) / Runes(...)）的括号内参数区。 */
    private static String argsOf(String token) {
        String block = blockAfter(token, "(");
        if (block == null) {
            return "";
        }
        return innerOf(block);
    }

    /** 按顶层逗号拆分（括号/方括号/字符串内的逗号不拆）。 */
    private static List<String> splitTopLevel(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inStr) {
                sb.append(c);
                if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inStr = true;
                    sb.append(c);
                }
                case '(', '[', '{' -> {
                    depth++;
                    sb.append(c);
                }
                case ')', ']', '}' -> {
                    depth--;
                    sb.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        parts.add(sb.toString());
                        sb.setLength(0);
                    } else {
                        sb.append(c);
                    }
                }
                default -> sb.append(c);
            }
        }
        parts.add(sb.toString());
        return parts;
    }

    /** 去掉字符串 token 的双引号。 */
    private static String quotedValue(String token) {
        String t = token.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
