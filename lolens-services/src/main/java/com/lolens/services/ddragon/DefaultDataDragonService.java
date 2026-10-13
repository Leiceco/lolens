package com.lolens.services.ddragon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lolens.services.ddragon.model.Champion;
import com.lolens.services.ddragon.model.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data Dragon 服务默认实现：启动时从 CDN 拉取版本、英雄、装备与符文数据并缓存到本地。
 * 网络不可用时回退到本地缓存目录。
 */
public class DefaultDataDragonService implements DataDragonService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataDragonService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DDragon_CDN = "https://ddragon.leagueoflegends.com";
    /** CommunityDragon 数据源：ddragon 的 runes-reforged.json 对部分地区(zh_CN)返回 403，改用 CDragon。 */
    private static final String CDRAGON_BASE = "https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Path cacheDir;
    private volatile String version = "15.1.1";
    // 后台线程写入、主线程读取，用并发容器保证可见性与线程安全
    private final Map<Integer, Champion> byId = new ConcurrentHashMap<>();
    private final Map<String, Champion> bySlug = new ConcurrentHashMap<>();
    private final Map<Integer, Item> itemsById = new ConcurrentHashMap<>();
    // 符文与符文页面：规范化名称 -> 图标相对路径（icon 字段形如 "perk-images/Styles/....png"）
    private final Map<String, String> runeIconByNorm = new ConcurrentHashMap<>();
    private final Map<String, String> runePageIconByNorm = new ConcurrentHashMap<>();
    // 符文与符文页面：规范化名称 -> id（runes-reforged.json 的 rune.id / page.id，page.id 即 LCU style id）
    private final Map<String, Integer> runeIdByNorm = new ConcurrentHashMap<>();
    private final Map<String, Integer> runePageIdByNorm = new ConcurrentHashMap<>();

    public DefaultDataDragonService(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception e) {
            log.warn("创建缓存目录失败: {}", cacheDir, e);
        }
    }

    /** 预加载英雄与装备数据（同步阻塞，建议在启动阶段调用）。 */
    public void preload() {
        try {
            version = resolveVersion();
            JsonNode champions = fetchChampionData(version);
            for (JsonNode node : champions) {
                List<String> tags = new ArrayList<>();
                node.path("tags").forEach(t -> tags.add(t.asText()));
                List<String> spellIcons = new ArrayList<>();
                node.path("spells").forEach(spell -> spellIcons.add(spell.path("icon").asText("")));
                // Data Dragon 约定：id=slug("Aatrox")、key=数字 id("266")。
                // 兼容探测：若实际返回相反（id 是数字、key 是 slug）也按真实语义取用。
                String idField = node.path("id").asText("");
                String keyField = node.path("key").asText("");
                boolean keyIsNumeric = keyField.matches("\\d+");
                String slug = keyIsNumeric ? idField : keyField;
                int championId;
                if (keyIsNumeric) {
                    championId = Integer.parseInt(keyField);
                } else {
                    // 仅当 id 字段为数字时兜底取 id，否则保持 0（找不到时上层自动降级）
                    championId = idField.matches("\\d+") ? Integer.parseInt(idField) : 0;
                }
                Champion champion = new Champion(
                        championId,
                        slug,
                        node.path("name").asText(),
                        node.path("title").asText(),
                        tags,
                        spellIcons
                );
                if (championId > 0) {
                    byId.put(championId, champion);
                }
                bySlug.put(champion.key().toLowerCase(Locale.ROOT), champion);
            }
            log.info("Data Dragon 已加载 {} 个英雄 (version={})", byId.size(), version);
        } catch (Exception e) {
            log.warn("Data Dragon 英雄预加载失败，使用本地缓存: {}", e.getMessage());
        }
        try {
            JsonNode itemsData = fetchJson("/cdn/" + version + "/data/zh_CN/item.json").path("data");
            itemsData.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                int id = Integer.parseInt(entry.getKey());
                itemsById.put(id, new Item(id, node.path("name").asText(), node.path("gold").path("total").asInt(0)));
            });
            log.info("Data Dragon 已加载 {} 件装备 (version={})", itemsById.size(), version);
        } catch (Exception e) {
            log.warn("Data Dragon 装备预加载失败，装备价格与名称将不可用: {}", e.getMessage());
        }
        try {
            loadRunesFromCDragon();
            log.info("Data Dragon 已加载 {} 个符文、{} 个符文页面、{} 个页面图标 (via CDragon, version={})",
                    runeIconByNorm.size(), runePageIdByNorm.size(), runePageIconByNorm.size(), version);
        } catch (Exception e) {
            log.warn("Data Dragon 符文预加载失败，符文图标将不可用: {}", e.getMessage());
        }
    }

    /**
     * 加载符文数据，优先级：CDragon 在线 -> jar 内置资源（rune-data/）。
     * <p>CDragon perkstyles.json 顶层为对象（含 styles 数组），perks.json 为数组；
     * 内置资源在打包时从 CDragon 固化，保证离线/网络受限时符文 id 与页面 id 仍可用。</p>
     */
    private void loadRunesFromCDragon() throws Exception {
        boolean onlineOk = true;
        try {
            JsonNode styles = fetchJsonUrl(CDRAGON_BASE + "perkstyles.json");
            JsonNode perks = fetchJsonUrl(CDRAGON_BASE + "perks.json");
            parseRuneData(styles, perks);
            // 防御：部分 CDN 变体/网络中间层可能返回异常结构导致页面解析为空，强制回退内置资源
            if (runePageIdByNorm.isEmpty()) {
                throw new IllegalStateException("在线符文页面 id 映射为空，疑似响应结构异常");
            }
        } catch (Exception e) {
            onlineOk = false;
            log.warn("CDragon 在线符文数据加载失败，回退内置资源: {}", e.getMessage());
            JsonNode styles = loadResourceJson("rune-data/perkstyles.json");
            JsonNode perks = loadResourceJson("rune-data/perks.json");
            parseRuneData(styles, perks);
        }
        log.info("Data Dragon 已加载 {} 个符文、{} 个符文页面、{} 个页面图标 (via {})",
                runeIconByNorm.size(), runePageIdByNorm.size(), runePageIconByNorm.size(),
                onlineOk ? "CDragon" : "内置资源");
    }

    /** 解析符文数据：perkstyles（顶层对象，styles 为数组）提供页面，perks（数组）提供全部符文。 */
    private void parseRuneData(JsonNode styles, JsonNode perks) {
        int styleCount = 0;
        int perkCount = 0;
        JsonNode styleArr = styles.isArray() ? styles : styles.path("styles");
        if (styleArr.isArray()) {
            styleCount = styleArr.size();
            for (JsonNode style : styleArr) {
                int id = style.path("id").asInt(0);
                String name = style.path("name").asText("");
                String icon = normalizeCDragonIcon(style.path("iconPath").asText(""));
                registerRunePage(id, name, icon);
            }
        }
        if (perks.isArray()) {
            perkCount = perks.size();
            for (JsonNode perk : perks) {
                int id = perk.path("id").asInt(0);
                String name = perk.path("name").asText("");
                String icon = normalizeCDragonIcon(perk.path("iconPath").asText(""));
                registerRune(id, name, icon);
            }
        }
        // 防御日志：响应结构存在但解析后映射为空（CDN 变体/字段缺失/异常结构），给出明确告警线索
        if (styleCount > 0 && runePageIdByNorm.isEmpty()) {
            log.warn("符文页面解析为空映射：styles={} 个但页面 id 映射为空，疑似 id/name 字段缺失或结构异常", styleCount);
        } else if (styleCount > 0 && runePageIconByNorm.isEmpty()) {
            log.warn("符文页面图标解析为空映射：styles={} 个但页面图标为空，疑似 iconPath 字段缺失", styleCount);
        }
        if (perkCount > 0 && runeIconByNorm.isEmpty()) {
            log.warn("符文解析为空映射：perks={} 个但符文映射为空，疑似 id/name/iconPath 字段缺失或结构异常", perkCount);
        }
    }

    /** 读取 classpath 内置 JSON 资源。 */
    private static JsonNode loadResourceJson(String resource) throws Exception {
        try (java.io.InputStream in = DefaultDataDragonService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少内置资源: " + resource);
            }
            return MAPPER.readTree(in);
        }
    }

    private void registerRunePage(int id, String name, String icon) {
        if (id <= 0) {
            return;
        }
        runePageIdByNorm.put(norm(String.valueOf(id)), id);
        if (!name.isBlank()) {
            runePageIdByNorm.putIfAbsent(norm(name), id);
        }
        if (!icon.isBlank()) {
            runePageIconByNorm.put(norm(String.valueOf(id)), icon);
            if (!name.isBlank()) {
                runePageIconByNorm.putIfAbsent(norm(name), icon);
            }
        }
    }

    private void registerRune(int id, String name, String icon) {
        if (id <= 0) {
            return;
        }
        runeIdByNorm.put(norm(String.valueOf(id)), id);
        if (!name.isBlank()) {
            runeIdByNorm.putIfAbsent(norm(name), id);
        }
        if (!icon.isBlank()) {
            runeIconByNorm.put(norm(String.valueOf(id)), icon);
            if (!name.isBlank()) {
                runeIconByNorm.putIfAbsent(norm(name), icon);
            }
        }
    }

    /** 归一化 CDragon 图标相对路径：兼容前导斜杠与 /lol-game-data/assets/v1/ 前缀，统一为 perk-images/... */
    private static String normalizeCDragonIcon(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String icon = raw.trim();
        int idx = icon.indexOf("perk-images");
        if (idx >= 0) {
            icon = icon.substring(idx);
        }
        return icon.startsWith("/") ? icon.substring(1) : icon;
    }

    private String resolveVersion() throws Exception {
        JsonNode versions = fetchJson("/api/versions.json");
        return versions.get(0).asText();
    }

    private JsonNode fetchChampionData(String v) throws Exception {
        JsonNode root = fetchJson("/cdn/" + v + "/data/zh_CN/champion.json");
        return root.path("data");
    }

    private JsonNode fetchJson(String path) throws Exception {
        return fetchJsonUrl(DDragon_CDN + path);
    }

    private JsonNode fetchJsonUrl(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Optional<Champion> champion(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Champion> championBySlug(String slug) {
        return Optional.ofNullable(bySlug.get(slug == null ? "" : slug.toLowerCase()));
    }

    @Override
    public String championIconUrl(int championId) {
        return champion(championId)
                .map(c -> DDragon_CDN + "/cdn/" + version + "/" + c.iconPath())
                .orElse("");
    }

    @Override
    public String itemIconUrl(int itemId) {
        return DDragon_CDN + "/cdn/" + version + "/img/item/" + itemId + ".png";
    }

    @Override
    public Optional<Item> item(int itemId) {
        return Optional.ofNullable(itemsById.get(itemId));
    }

    @Override
    public Optional<Item> itemByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String target = norm(name);
        return itemsById.values().stream()
                .filter(item -> norm(item.name()).equals(target))
                .findFirst();
    }

    @Override
    public String runeIconUrl(String runeName) {
        return iconUrlBy(runeIconByNorm, runeName);
    }

    @Override
    public String runePageIconUrl(String pageName) {
        return iconUrlBy(runePageIconByNorm, pageName);
    }

    @Override
    public Optional<Integer> runeIdByName(String runeName) {
        return idBy(runeIdByNorm, runeName);
    }

    @Override
    public Optional<Integer> runePageIdByName(String pageName) {
        return idBy(runePageIdByNorm, pageName);
    }

    private Optional<Integer> idBy(Map<String, Integer> ids, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ids.get(norm(name)));
    }

    private String iconUrlBy(Map<String, String> icons, String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String icon = icons.get(norm(name));
        if (icon == null || icon.isBlank()) {
            return "";
        }
        // 符文图标走 /cdn/img/ 前缀，icon 字段形如 "perk-images/Styles/....png"
        return DDragon_CDN + "/cdn/img/" + icon;
    }

    @Override
    public String spellIconUrl(String championSlug, int abilityIndex) {
        Optional<Champion> champion = championBySlug(championSlug);
        if (champion.isEmpty()) {
            return "";
        }
        List<String> icons = champion.get().spellIcons();
        if (abilityIndex < 0 || abilityIndex >= icons.size()) {
            return "";
        }
        String icon = icons.get(abilityIndex);
        if (icon == null || icon.isBlank()) {
            return "";
        }
        // champion.json 的 spells[].icon 形如 "spell/LuxLightBinding.png"
        return DDragon_CDN + "/cdn/" + version + "/img/" + icon;
    }

    /** LCU/Data Dragon summoner spell id -> Data Dragon 图标文件名（summoner.json 的 key）。 */
    private static final Map<Integer, String> SUMMONER_SPELL_ICONS = Map.ofEntries(
            Map.entry(1, "SummonerBoost"),      // 净化 Cleanse
            Map.entry(2, "SummonerRepair"),     // 修复 Repair（攻城模式）
            Map.entry(3, "SummonerExhaust"),    // 虚弱 Exhaust
            Map.entry(4, "SummonerFlash"),      // 闪现 Flash
            Map.entry(5, "SummonerRally"),      // 战争怒吼 Rally
            Map.entry(6, "SummonerHaste"),      // 疾跑 Ghost
            Map.entry(7, "SummonerHeal"),       // 治疗 Heal
            Map.entry(11, "SummonerSmite"),     // 惩戒 Smite
            Map.entry(12, "SummonerTeleport"),  // 传送 Teleport
            Map.entry(13, "SummonerMana"),      // 清晰 Clarity
            Map.entry(14, "SummonerDot"),       // 点燃 Ignite
            Map.entry(21, "SummonerBarrier"),   // 屏障 Barrier
            Map.entry(32, "SummonerSnowball"),  // 雪球 Mark
            Map.entry(35, "SummonerPoroThrow"));// 魄罗投掷 Poro

    @Override
    public String summonerSpellIconUrl(int summonerSpellId) {
        String key = SUMMONER_SPELL_ICONS.get(summonerSpellId);
        if (key == null || key.isBlank()) {
            return "";
        }
        return DDragon_CDN + "/cdn/" + version + "/img/spell/" + key + ".png";
    }

    /** 规范化名称：去空格/撇号/连字符/下划线并转小写，用于跨语言 key/name 匹配。 */
    private static String norm(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s'’_\\-.]", "").toLowerCase(Locale.ROOT);
    }
}
