package com.lolens.modules.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.event.ChampSelectUpdated;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.model.ChampSelectSession;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.ddragon.model.Champion;
import com.lolens.services.lcu.DefaultLcuPerksService;
import com.lolens.services.lcu.LcuClient;
import com.lolens.services.lcu.LcuPerksService;
import com.lolens.services.opgg.OpggClient;
import com.lolens.services.opgg.model.ChampionAnalysis;
import com.lolens.services.opgg.model.Position;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;
import com.lolens.ui.page.PageRegistry;
import com.lolens.ui.sidebar.NavPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 出装推荐模块：选人阶段读取我方英雄与位置，向 OP.GG 服务查询推荐出装并展示。
 * 网络不可用时自动降级为本地通用推荐；技能加点优先 OP.GG，否则本地规则并标注。
 */
public class BuildRecommendationModule implements LolModule {

    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private BuildPanel panel;
    /** 主窗口「出装/天赋」页，与叠加面板同源驱动。 */
    private BuildPageController page;
    private ModuleContext ctx;
    private Subscription champSub;

    /** 当前选人代际：ChampSelectUpdated 每 2s 一次，仅英雄变化时重新加载，回调按代际丢弃陈旧结果。 */
    private volatile long generation = 0;
    private volatile long lastChampionKey = Long.MIN_VALUE;

    @Override
    public String id() {
        return "build-recommendation";
    }

    @Override
    public String displayName() {
        return "出装推荐";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        DataDragonService dd = ctx.services().find(DataDragonService.class).orElse(null);
        LcuPerksService perks = ctx.services().find(LcuClient.class)
                .map(lcu -> (LcuPerksService) new DefaultLcuPerksService(lcu, dd))
                .orElse(null);
        this.panel = new BuildPanel(dd, perks);
        this.page = new BuildPageController(dd, perks);
        ctx.services().register(BuildRecommendationService.class,
                ctx.services().find(OpggClient.class)
                        .<BuildRecommendationService>map(DefaultBuildRecommendationService::new)
                        .orElseGet(DefaultBuildRecommendationService::new));
        champSub = ctx.events().subscribe(ChampSelectUpdated.class, this::onChampSelect);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.LEFT_BOTTOM));
        ctx.services().find(PageRegistry.class)
                .ifPresent(reg -> reg.register(NavPage.BUILD, page.node()));
    }

    private void onChampSelect(ChampSelectUpdated event) {
        ChampSelectSession session = event.session();
        if (session.myChampionId() == null) {
            // 尚未锁定英雄：使在途回调失效并复位
            generation++;
            lastChampionKey = Long.MIN_VALUE;
            panel.reset();
            page.reset();
            return;
        }
        DataDragonService dd = ctx.services().find(DataDragonService.class).orElse(null);
        String slug = Optional.ofNullable(dd)
                .flatMap(d -> d.champion(session.myChampionId()))
                .map(Champion::key)
                .orElse("");
        if (slug.isEmpty()) {
            return;
        }
        String displayName = Optional.ofNullable(dd)
                .flatMap(d -> d.champion(session.myChampionId()))
                .map(Champion::name)
                .orElse(slug);
        Position parsed = Position.fromSlug(session.myPosition());
        List<String> heroTags = Optional.ofNullable(dd)
                .flatMap(d -> d.champion(session.myChampionId()))
                .map(Champion::tags)
                .orElse(List.of());
        // 位置未知/未分配时按英雄标签推断合理位置（OP.GG 不接受 position=all，见 DefaultOpggClient.toOpggPosition）
        Position position = parsed != null ? parsed : inferPosition(heroTags);
        int myChampionId = session.myChampionId();

        // 事件每 2s 触发一次：只有英雄（含位置）真正变化时才重新加载，
        // 避免面板每 2s 闪回"加载中"、反复创建线程与重复请求 OP.GG
        long key = (((long) myChampionId) << 16) | ((position == null ? 0 : position.ordinal() + 1) & 0xFFFFL);
        boolean changed = key != lastChampionKey;
        lastChampionKey = key;
        long gen = ++generation;

        if (!changed) {
            return;
        }
        panel.showLoading(displayName);
        page.showLoading(displayName);
        // 图2 召唤师技能：LCU 实时读取，失败按位置本地兜底（闪现+传送/惩戒/引燃/治疗）
        Position finalPosition = position;
        new Thread(() -> {
            List<Integer> spells = loadSummonerSpells();
            boolean fromLcu = spells != null && !spells.isEmpty();
            if (!fromLcu) {
                spells = fallbackSpells(finalPosition);
            }
            panel.setSummonerSpells(spells, fromLcu ? "LCU 实时" : "本地兜底");
        }, "lolens-spell-loader").start();
        ctx.services().find(BuildRecommendationService.class).ifPresent(svc ->
                svc.recommend(slug, position)
                        .thenAccept(analysis -> {
                            if (gen != generation) {
                                return; // 期间英雄已变化，丢弃陈旧结果
                            }
                            List<String> skills = analysis.hasSkillOrder()
                                    ? analysis.skillOrder()
                                    : SkillOrderPlanner.plan(heroTags, position);
                            panel.update(displayName, myChampionId, analysis, skills,
                                    analysis.hasSkillOrder() ? "OP.GG" : "本地规则", false);
                            page.update(displayName, analysis, skills,
                                    analysis.hasSkillOrder() ? "OP.GG" : "本地规则", false);
                        })
                        .exceptionally(ex -> {
                            if (gen != generation) {
                                return null;
                            }
                            ctx.logger().warn("获取出装推荐失败: {}", ex.getMessage());
                            ChampionAnalysis fallback = DefaultBuildRecommendationService.localFallback(slug);
                            panel.update(displayName, myChampionId, fallback,
                                    SkillOrderPlanner.plan(heroTags, position), "本地规则", true);
                            page.update(displayName, fallback,
                                    SkillOrderPlanner.plan(heroTags, position), "本地规则", true);
                            return null;
                        }));
    }

    /** LCU 实时读取当前召唤师选人阶段的召唤师技能；未连接/无匹配返回空列表。 */
    private List<Integer> loadSummonerSpells() {
        LcuClient lcu = ctx.services().find(LcuClient.class).orElse(null);
        if (lcu == null) {
            return List.of();
        }
        try {
            JsonNode me = lcu.get("/lol-summoner/v1/current-summoner", JsonNode.class);
            long myId = me == null ? 0 : me.path("summonerId").asLong(0);
            if (myId == 0) {
                return List.of();
            }
            JsonNode session = lcu.get("/lol-champ-select/v1/session", JsonNode.class);
            if (session == null) {
                return List.of();
            }
            JsonNode myTeam = session.path("myTeam");
            for (JsonNode p : myTeam) {
                if (p.path("summonerId").asLong(0) == myId) {
                    List<Integer> result = new ArrayList<>();
                    int s1 = p.path("spell1Id").asInt(0);
                    int s2 = p.path("spell2Id").asInt(0);
                    if (s1 > 0) {
                        result.add(s1);
                    }
                    if (s2 > 0) {
                        result.add(s2);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            ctx.logger().debug("读取召唤师技能失败: {}", e.getMessage());
        }
        return List.of();
    }

    /** 本地兜底召唤师技能：按位置取常用组合（LCU id：闪现4/传送12/惩戒11/引燃14/治疗7）。 */
    private static List<Integer> fallbackSpells(Position position) {
        return switch (position == null ? Position.MIDDLE : position) {
            case TOP -> List.of(4, 12);
            case JUNGLE -> List.of(4, 11);
            case MIDDLE -> List.of(4, 14);
            case BOTTOM, UTILITY -> List.of(4, 7);
            default -> List.of(4, 12);
        };
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onUnload() {
        if (champSub != null) {
            champSub.close();
        }
    }

    /**
     * 位置未知/未分配时按英雄标签推断合理位置：Mage/Assassin→MIDDLE，Marksman→BOTTOM，
     * Support→UTILITY，Tank/Fighter→TOP，其余默认 MIDDLE。避免发送 OP.GG 不接受的 position=all。
     */
    private static Position inferPosition(List<String> heroTags) {
        if (heroTags == null) {
            return Position.MIDDLE;
        }
        if (heroTags.stream().anyMatch(t -> t.equalsIgnoreCase("Marksman"))) {
            return Position.BOTTOM;
        }
        if (heroTags.stream().anyMatch(t -> t.equalsIgnoreCase("Support"))) {
            return Position.UTILITY;
        }
        if (heroTags.stream().anyMatch(t -> t.equalsIgnoreCase("Tank")
                || t.equalsIgnoreCase("Fighter"))) {
            return Position.TOP;
        }
        if (heroTags.stream().anyMatch(t -> t.equalsIgnoreCase("Mage")
                || t.equalsIgnoreCase("Assassin"))) {
            return Position.MIDDLE;
        }
        return Position.MIDDLE;
    }
}
