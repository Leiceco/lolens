package com.lolens.services.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.opgg.model.ChampionAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一键配置天赋默认实现：通过 LCU /lol-perks/v1/ 系列接口创建/更新符文页并设为当前。
 * <p>符文 id 优先使用 OP.GG 返回的真实 id（primary_rune_ids 等）；缺失时按名称经
 * Data Dragon runes-reforged.json 名称→id 映射兜底。</p>
 */
public class DefaultLcuPerksService implements LcuPerksService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLcuPerksService.class);

    /** 小符文兜底：攻速 / 自适应 / 生命值（OP.GG 未返回 stat_mod_ids 时使用）。 */
    private static final List<Integer> DEFAULT_STAT_MOD_IDS = List.of(5008, 5005, 5003);

    private final LcuClient lcu;
    private final DataDragonService dd;

    public DefaultLcuPerksService(LcuClient lcu, DataDragonService dd) {
        this.lcu = lcu;
        this.dd = dd;
    }

    @Override
    public boolean isConnected() {
        return lcu != null && lcu.state() == ConnectionState.CONNECTED;
    }

    @Override
    public boolean isChampionSelect() {
        try {
            if (lcu == null) {
                return false;
            }
            String phase = lcu.get("/lol-gameflow/v1/gameflow-phase", String.class);
            return "ChampionSelect".equals(phase);
        } catch (Exception e) {
            log.debug("查询 gameflow 阶段失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public ApplyResult apply(String pageName, ChampionAnalysis.RecommendedRune rune) {
        if (!isConnected()) {
            return new ApplyResult(false, pageName, null, "LCU 未连接");
        }
        if (pageName == null || pageName.isBlank()) {
            return new ApplyResult(false, pageName, null, "符文页名称为空");
        }
        if (rune == null || rune.name() == null || rune.name().isBlank()) {
            return new ApplyResult(false, pageName, null, "暂无符文推荐数据");
        }

        // 1) 组装符文页参数（id 优先 OP.GG，缺失按名称映射兜底）
        Integer primaryStyleId = firstNonZero(rune.primaryStyleId(), dd == null ? Optional.empty() : dd.runePageIdByName(rune.name()));
        Integer subStyleId = firstNonZero(rune.secondaryStyleId(),
                dd == null ? Optional.empty() : dd.runePageIdByName(rune.secondaryPageName()));
        if (primaryStyleId == null) {
            return new ApplyResult(false, pageName, null, "无法识别主系页面: " + rune.name());
        }
        if (subStyleId == null) {
            return new ApplyResult(false, pageName, null, "无法识别副系页面: " + rune.secondaryPageName());
        }

        List<Integer> perkIds = new ArrayList<>();
        perkIds.addAll(resolvePrimaryPerks(rune));
        perkIds.addAll(resolveSecondaryPerks(rune));
        perkIds.addAll(resolveStatMods(rune));
        if (perkIds.size() < 9) {
            return new ApplyResult(false, pageName, null,
                    "符文数据不完整（需要主系4+副系2+小符文3，当前 " + perkIds.size() + " 个）");
        }

        try {
            // 2) 查找同名页：存在则更新复用，避免重复创建
            JsonNode pages = lcu.get("/lol-perks/v1/pages", JsonNode.class);
            Integer existingId = null;
            if (pages != null && pages.isArray()) {
                for (JsonNode page : pages) {
                    if (pageName.equals(page.path("name").asText())) {
                        existingId = page.path("id").asInt();
                        break;
                    }
                }
            }

            int pageId;
            if (existingId != null) {
                Map<String, Object> body = pageBody(pageName, primaryStyleId, subStyleId, perkIds);
                body.put("id", existingId);
                lcu.put("/lol-perks/v1/pages/" + existingId, body);
                pageId = existingId;
            } else {
                JsonNode created = lcu.post("/lol-perks/v1/pages", pageBody(pageName, primaryStyleId, subStyleId, perkIds), JsonNode.class);
                pageId = created == null ? -1 : created.path("id").asInt(-1);
                if (pageId <= 0) {
                    return new ApplyResult(false, pageName, null, "创建符文页失败：客户端未返回页面 id");
                }
            }

            // 3) 设为当前使用；PATCH 失败时回退 currentpage 接口
            boolean currentSet = false;
            try {
                lcu.patch("/lol-perks/v1/pages/" + pageId, Map.of("current", true), JsonNode.class);
                currentSet = true;
            } catch (Exception e) {
                log.debug("PATCH 设置当前符文页失败，回退 currentpage: {}", e.getMessage());
                try {
                    lcu.put("/lol-perks/v1/currentpage", Map.of("id", pageId));
                    currentSet = true;
                } catch (Exception e2) {
                    log.warn("设置当前符文页失败: {}", e2.getMessage());
                }
            }
            if (!currentSet) {
                return new ApplyResult(true, pageName, pageId, "符文页已写入，但设为当前失败（可手动在客户端切换）");
            }
            return new ApplyResult(true, pageName, pageId, "已写入并设为当前符文页");
        } catch (Exception e) {
            log.warn("一键配置天赋失败: {}", e.getMessage());
            return new ApplyResult(false, pageName, null, "LCU 写入失败: " + e.getMessage());
        }
    }

    /** 主系符文：优先 OP.GG 4 个 id；不足时按名称映射基石补位（主系小符文一般随 id 返回）。 */
    private List<Integer> resolvePrimaryPerks(ChampionAnalysis.RecommendedRune rune) {
        List<Integer> ids = new ArrayList<>(rune.primaryRuneIds());
        if (ids.size() < 4) {
            Integer keystoneId = dd == null ? null : dd.runeIdByName(rune.keystone()).orElse(null);
            if (keystoneId != null && !ids.contains(keystoneId)) {
                ids.add(0, keystoneId);
            }
            for (String name : rune.primaryRunes()) {
                if (ids.size() >= 4) {
                    break;
                }
                Integer id = dd == null ? null : dd.runeIdByName(name).orElse(null);
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids.size() > 4 ? ids.subList(0, 4) : ids;
    }

    /** 副系符文：优先 OP.GG 2 个 id；不足时按名称映射兜底。 */
    private List<Integer> resolveSecondaryPerks(ChampionAnalysis.RecommendedRune rune) {
        List<Integer> ids = new ArrayList<>(rune.secondaryRuneIds());
        if (ids.size() < 2) {
            for (String name : rune.secondaryRunes()) {
                if (ids.size() >= 2) {
                    break;
                }
                Integer id = dd == null ? null : dd.runeIdByName(name).orElse(null);
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids.size() > 2 ? ids.subList(0, 2) : ids;
    }

    /** 小符文：优先 OP.GG stat_mod_ids 前 3；缺失时使用通用兜底。 */
    private List<Integer> resolveStatMods(ChampionAnalysis.RecommendedRune rune) {
        List<Integer> ids = new ArrayList<>(rune.statModIds());
        if (ids.isEmpty()) {
            ids.addAll(DEFAULT_STAT_MOD_IDS);
        }
        return ids.size() > 3 ? ids.subList(0, 3) : ids;
    }

    private static Map<String, Object> pageBody(String name, int primaryStyleId, int subStyleId, List<Integer> perkIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("primaryStyleId", primaryStyleId);
        body.put("subStyleId", subStyleId);
        body.put("selectedPerkIds", perkIds);
        return body;
    }

    /** 优先取显式 id，为空时取 Optional 兜底值。 */
    private static Integer firstNonZero(Integer explicit, Optional<Integer> fallback) {
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        return fallback.orElse(null);
    }
}
