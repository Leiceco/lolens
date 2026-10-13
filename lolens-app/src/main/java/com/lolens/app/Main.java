package com.lolens.app;

import com.lolens.kernel.Kernel;
import com.lolens.kernel.config.AppSettings;
import com.lolens.modules.autoaccept.AutoAcceptModule;
import com.lolens.modules.autoreply.AutoReplyModule;
import com.lolens.modules.build.BuildRecommendationModule;
import com.lolens.modules.chatstatus.ChatStatusModule;
import com.lolens.modules.counter.CounterRecommendationModule;
import com.lolens.modules.core.overlay.CoreOverlayModule;
import com.lolens.modules.enemy.EnemyStatsModule;
import com.lolens.modules.honor.HonorModule;
import com.lolens.modules.lobbyflow.LobbyFlowModule;
import com.lolens.modules.matchhistory.MatchHistoryModule;
import com.lolens.modules.pickban.PickBanModule;
import com.lolens.modules.respawn.RespawnTimerModule;
import com.lolens.modules.skill.SkillTimerModule;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.ddragon.DefaultDataDragonService;
import com.lolens.services.lcu.DefaultLcuClient;
import com.lolens.services.lcu.LcuClient;
import com.lolens.services.live.DefaultLiveClientApi;
import com.lolens.services.live.LiveClientApi;
import com.lolens.services.opgg.DefaultOpggClient;
import com.lolens.services.opgg.OpggClient;
import com.lolens.ui.UiLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * LoLens 应用入口：装配数据层服务与功能模块，然后交给 UI 层启动。
 *
 * <pre>
 * 架构分层：UI (lolens-ui) → Modules (lolens-modules/*) → Kernel (lolens-kernel)
 *           → Services (lolens-services)
 * 入口流程：
 *   1. 创建内核（事件总线/服务注册表/模块注册表/配置/热键/调度器）
 *   2. 注册数据层服务（LCU / Live Client / OP.GG / Data Dragon / 缓存）
 *   3. 注册 9 个功能模块（核心叠加 / 出装推荐 / 对位推荐 / 敌方战绩 / 技能CD / 自动接受 / 重生倒计时 / 战绩查询 / 自动选人禁用）
 *   4. UiLauncher.launchWith 注入内核并启动 JavaFX
 * </pre>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("=== LoLens 启动 ===");
        Kernel kernel = new Kernel();
        AppConfig config = new AppConfig();
        AppSettings defaults = new AppSettings(true, true, true, true, true, true, true, true,
                true, true, true, true, 0, config.overlayOpacity(), config.sendCooldownSeconds());
        AppSettings settings = kernel.config().load("app", AppSettings.class, defaults);
        // 首次启动落盘默认值，供 UI 层读到一致的透明度/冷却配置
        kernel.config().save("app", settings);
        registerServices(kernel, config);
        registerModules(kernel, settings, config);
        startDataProvider(kernel, config);
        UiLauncher.launchWith(kernel, args);
    }

    /** 注册数据层服务到内核 ServiceRegistry。 */
    private static void registerServices(Kernel kernel, AppConfig config) {
        kernel.services().register(LcuClient.class, new DefaultLcuClient(Path.of(config.lockfilePath())));
        kernel.services().register(LiveClientApi.class, new DefaultLiveClientApi());
        kernel.services().register(OpggClient.class, new DefaultOpggClient(config.opggMcpUrl(), config.opggRegion()));

        Path ddragonCache = Path.of(System.getProperty("user.home"), ".lolens", "ddragon");
        DefaultDataDragonService ddragon = new DefaultDataDragonService(ddragonCache);
        kernel.services().register(DataDragonService.class, ddragon);
        // 英雄数据预加载不阻塞启动
        Thread preload = new Thread(() -> {
            try {
                ddragon.preload();
            } catch (Exception e) {
                log.warn("Data Dragon 预加载异常: {}", e.getMessage());
            }
        }, "ddragon-preload");
        preload.setDaemon(true);
        preload.start();
        log.info("数据层服务注册完成");
    }

    /** 按设置注册功能模块（核心叠加常驻，其余按开关决定）。 */
    private static void registerModules(Kernel kernel, AppSettings settings, AppConfig config) {
        if (settings.buildEnabled()) {
            kernel.registerModule(new BuildRecommendationModule());
        }
        if (settings.counterEnabled()) {
            kernel.registerModule(new CounterRecommendationModule());
        }
        if (settings.enemyStatsEnabled()) {
            kernel.registerModule(new EnemyStatsModule());
        }
        if (settings.skillTimerEnabled()) {
            kernel.registerModule(new SkillTimerModule());
        }
        if (settings.autoAcceptEnabled()) {
            // 延迟秒数使用可写设置 AppSettings（设置页保存），而非只读的 classpath properties
            kernel.registerModule(new AutoAcceptModule(config.autoAcceptPollIntervalSeconds(),
                    settings.autoAcceptDelaySeconds()));
        }
        if (settings.respawnTimerEnabled()) {
            kernel.registerModule(new RespawnTimerModule(config.respawnPollIntervalSeconds()));
        }
        if (settings.matchHistoryEnabled()) {
            kernel.registerModule(new MatchHistoryModule(config.matchHistoryQueryCount()));
        }
        if (settings.pickBanEnabled()) {
            kernel.registerModule(new PickBanModule(config.pickBanPollIntervalSeconds(),
                    config.pickBanPickChampionIds(), config.pickBanBanChampionIds()));
        }
        if (settings.honorEnabled()) {
            kernel.registerModule(new HonorModule(config.honorPollIntervalSeconds(),
                    config.honorAlliesOnly(), config.honorType()));
        }
        if (settings.autoReplyEnabled()) {
            kernel.registerModule(new AutoReplyModule(config.autoReplyPollIntervalSeconds(),
                    config.autoReplyRules()));
        }
        if (settings.lobbyFlowEnabled()) {
            kernel.registerModule(new LobbyFlowModule(config.lobbyFlowPollIntervalSeconds(),
                    config.playAgainDelaySeconds(), config.reconnectDelaySeconds()));
        }
        if (settings.chatStatusEnabled()) {
            kernel.registerModule(new ChatStatusModule(config.chatStatusAvailability(),
                    config.chatStatusMessage(), config.chatStatusRankedQueue(),
                    config.chatStatusRankedTier(), config.chatStatusRankedDivision()));
        }
        kernel.registerModule(new CoreOverlayModule());
        log.info("功能模块注册完成");
    }

    /** 启动数据采集器，闭环事件数据流。 */
    private static void startDataProvider(Kernel kernel, AppConfig config) {
        GameDataProvider provider = new GameDataProvider(
                kernel.events(),
                kernel.services().require(LcuClient.class),
                kernel.services().require(LiveClientApi.class),
                kernel.scheduler(),
                config.livePollIntervalSeconds());
        provider.start();
        // 内核停止时释放采集器与 LCU 连接线程，避免资源泄漏与对已关停事件总线的发布
        kernel.addShutdownHook(provider::stop);
    }
}
