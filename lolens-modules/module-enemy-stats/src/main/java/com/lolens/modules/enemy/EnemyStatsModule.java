package com.lolens.modules.enemy;

import com.lolens.kernel.event.ChampSelectUpdated;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.model.ChampSelectSession;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.opgg.OpggClient;
import com.lolens.services.opgg.model.SummonerProfile;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

import java.util.List;

/**
 * 敌方战绩速查模块：选人阶段批量查询敌方五名召唤师的段位与胜率，供 Ban/Pick 参考。
 */
public class EnemyStatsModule implements LolModule {

    /** 国服 OP.GG 查询标签：国服为纯昵称，tagLine 语义与 Riot ID 不同，需按 OP.GG 国服接口调整。 */
    private static final String OPGG_TAG_LINE = "CN";

    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private EnemyStatsPanel panel;
    private ModuleContext ctx;
    private Subscription champSub;
    /** 选人代际：回调按代际校验，上一局迟到的响应不会写进本局面板。 */
    private volatile long generation = 0;

    @Override
    public String id() {
        return "enemy-stats";
    }

    @Override
    public String displayName() {
        return "敌方战绩速查";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        this.panel = new EnemyStatsPanel();
        champSub = ctx.events().subscribe(ChampSelectUpdated.class, this::onChampSelect);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.RIGHT_TOP));
    }

    private void onChampSelect(ChampSelectUpdated event) {
        ChampSelectSession session = event.session();
        // 先清空旧数据，避免敌人数量变化时残留上一局的战绩行
        panel.reset();
        long gen = ++generation;
        if (session.theirTeam() == null || session.theirTeam().isEmpty()) {
            return;
        }
        List<ChampSelectSession.ChampSelectPlayer> enemies = session.theirTeam().stream()
                .filter(p -> p.summonerName() != null && !p.summonerName().isBlank())
                .limit(5)
                .toList();
        ctx.services().find(OpggClient.class).ifPresent(opgg -> {
            for (ChampSelectSession.ChampSelectPlayer enemy : enemies) {
                // Riot ID 账号的 summonerName 为 "name#TAG"，按 '#' 拆分；无 tag（国服昵称）用默认 CN
                String name = enemy.summonerName();
                int hash = name.indexOf('#');
                String gameName = hash > 0 ? name.substring(0, hash) : name;
                String tagLine = hash > 0 && hash < name.length() - 1
                        ? name.substring(hash + 1) : OPGG_TAG_LINE;
                opgg.getSummonerProfile(gameName, tagLine)
                        .thenAccept(profile -> {
                            if (gen == generation) {
                                panel.updatePlayer(name, profile, false);
                            }
                        })
                        .exceptionally(ex -> {
                            if (gen == generation) {
                                panel.updatePlayer(name, null, true);
                            }
                            return null;
                        });
            }
        });
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
