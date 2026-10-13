package com.lolens.modules.counter;

import com.lolens.kernel.event.ChampSelectUpdated;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.model.ChampSelectSession;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.ddragon.model.Champion;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

import java.util.List;

/**
 * 对位推荐模块：选人阶段分析敌方已选英雄，基于本地克制表推荐克制选择。
 */
public class CounterRecommendationModule implements LolModule {

    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private CounterPanel panel;
    private ModuleContext ctx;
    private Subscription champSub;

    @Override
    public String id() {
        return "counter-recommendation";
    }

    @Override
    public String displayName() {
        return "对位推荐";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        this.panel = new CounterPanel();
        ctx.services().register(CounterRecommendationService.class,
                ctx.services().find(DataDragonService.class)
                        .<CounterRecommendationService>map(DefaultCounterRecommendationService::new)
                        .orElseGet(DefaultCounterRecommendationService::new));
        champSub = ctx.events().subscribe(ChampSelectUpdated.class, this::onChampSelect);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.RIGHT_BOTTOM));
    }

    private void onChampSelect(ChampSelectUpdated event) {
        ChampSelectSession session = event.session();
        if (session.theirTeam() == null || session.theirTeam().isEmpty()) {
            panel.reset();
            return;
        }
        DataDragonService dd = ctx.services().find(DataDragonService.class).orElse(null);
        List<String> enemySlugs = session.theirTeam().stream()
                .filter(p -> p.championId() != null && p.championId() > 0)
                .map(p -> java.util.Optional.ofNullable(dd)
                        .flatMap(d -> d.champion(p.championId()))
                        .map(Champion::key)
                        .orElse(""))
                .filter(s -> !s.isEmpty())
                .toList();
        if (enemySlugs.isEmpty()) {
            // 敌方尚未锁定英雄：清空面板，避免上一局推荐残留到新一局
            panel.reset();
            return;
        }
        ctx.services().find(CounterRecommendationService.class)
                .ifPresent(svc -> panel.update(svc.recommendCounters(enemySlugs)));
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
}
