package com.lolens.modules.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.event.GameDataUpdated;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.model.LiveGameData;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.kernel.scheduler.Scheduler;
import com.lolens.services.lcu.LcuClient;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 技能 CD 计时模块：对局中通过热键记录 Q/W/E/R 与召唤师技能施放，
 * 实时展示剩余冷却（召唤师技能），并按 Alt+Q 将摘要发送到游戏内聊天。
 *
 * <p>热键约定（与 README 一致）：Alt+1~4 记录 Q/W/E/R 技能使用；
 * Alt+5 记录召唤师技能使用；Alt+H 显隐叠加窗口（由 UI 层注册）；
 * Alt+Q 发送当前 CD 摘要。</p>
 */
public class SkillTimerModule implements LolModule {

    private final SkillTracker tracker = new SkillTracker();
    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private SkillTimerPanel panel;
    private ModuleContext ctx;
    private Subscription dataSub;
    private Subscription phaseSub;
    private ScheduledFuture<?> tickTask;
    /** 当前召唤师名（登录后缓存），用于定位对局中的“我方”。 */
    private volatile String cachedSelfName;
    /** 消息发送冷却（秒）与上次发送时间戳。 */
    private volatile int sendCooldownSeconds = 5;
    private volatile long lastSendAt = 0;

    @Override
    public String id() {
        return "skill-timer";
    }

    @Override
    public String displayName() {
        return "技能CD计时";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        this.panel = new SkillTimerPanel(tracker);
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        sendCooldownSeconds = settings.sendCooldownSeconds();
        dataSub = ctx.events().subscribe(GameDataUpdated.class, this::onGameData);
        phaseSub = ctx.events().subscribe(GamePhaseChanged.class, this::onPhaseChanged);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.LEFT_BOTTOM));
    }

    @Override
    public void onEnable() {
        Set<GamePhase> inGame = Set.of(GamePhase.IN_PROGRESS);
        // Alt+1~4 记录 Q/W/E/R 技能使用；Alt+5 记录召唤师技能使用
        ctx.hotkeys().register("Alt+1", inGame, () -> tracker.recordCast("Q"));
        ctx.hotkeys().register("Alt+2", inGame, () -> tracker.recordCast("W"));
        ctx.hotkeys().register("Alt+3", inGame, () -> tracker.recordCast("E"));
        ctx.hotkeys().register("Alt+4", inGame, () -> tracker.recordCast("R"));
        ctx.hotkeys().register("Alt+5", inGame, () -> tracker.recordCast("SPELL1"));
        ctx.hotkeys().register("Alt+6", inGame, () -> tracker.recordCast("SPELL2"));
        ctx.hotkeys().register("Alt+Q", inGame, this::sendCooldownSummary);
        // 每秒刷新面板剩余 CD
        tickTask = ctx.scheduler().scheduleAtFixedRate(panel::refresh, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        ctx.hotkeys().unregister("Alt+1");
        ctx.hotkeys().unregister("Alt+2");
        ctx.hotkeys().unregister("Alt+3");
        ctx.hotkeys().unregister("Alt+4");
        ctx.hotkeys().unregister("Alt+5");
        ctx.hotkeys().unregister("Alt+6");
        ctx.hotkeys().unregister("Alt+Q");
        if (tickTask != null) {
            tickTask.cancel(true);
        }
    }

    private void onGameData(GameDataUpdated event) {
        LiveGameData data = event.data();
        if (data == null) {
            return;
        }
        tracker.setGameTime(data.gameTimeSeconds());
        panel.update(data, resolveSelf(data));
    }

    /** 通过 LCU current-summoner 定位自己，而非假设蓝方。 */
    private LiveGameData.LivePlayer resolveSelf(LiveGameData data) {
        String selfName = resolveSelfName();
        if (selfName == null || selfName.isBlank()) {
            return null;
        }
        return data.players().stream()
                .filter(p -> selfName.equalsIgnoreCase(p.summonerName()))
                .findFirst()
                .orElse(null);
    }

    private String resolveSelfName() {
        String name = cachedSelfName;
        if (name != null) {
            return name;
        }
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                JsonNode s = lcu.get("/lol-summoner/v1/current-summoner", JsonNode.class);
                if (s != null && s.has("displayName")) {
                    String display = s.path("displayName").asText("");
                    // displayName 为完整 Riot ID（gameName#tag），live data 中 summonerName 不含 tag，需截取 gameName
                    int hash = display.indexOf('#');
                    cachedSelfName = hash >= 0 ? display.substring(0, hash) : display;
                }
            } catch (Exception ignored) {
                // LCU 未就绪时保持 null，下次轮询再试
            }
        });
        return cachedSelfName;
    }

    private void onPhaseChanged(GamePhaseChanged event) {
        if (event.to() == GamePhase.IN_PROGRESS) {
            tracker.reset();
            panel.setInGame(true);
        } else {
            panel.setInGame(false);
        }
    }

    /** Alt+Q：把当前 CD 摘要发送到游戏聊天（失败可忽略，受发送冷却限制）。 */
    private void sendCooldownSummary() {
        long now = System.currentTimeMillis();
        if (now - lastSendAt < sendCooldownSeconds * 1000L) {
            ctx.logger().debug("发送冷却中，忽略本次发送");
            return;
        }
        lastSendAt = now;
        String summary = panel.buildSummary();
        ctx.logger().info("发送技能CD摘要: {}", summary);
        panel.showSent(summary);
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                lcu.post("/lol-chat/v1/messages",
                        java.util.Map.of("body", summary, "type", "chat"),
                        com.fasterxml.jackson.databind.JsonNode.class);
            } catch (Exception e) {
                ctx.logger().warn("发送技能CD摘要失败(可忽略): {}", e.getMessage());
            }
        });
    }

    @Override
    public void onUnload() {
        if (dataSub != null) {
            dataSub.close();
        }
        if (phaseSub != null) {
            phaseSub.close();
        }
    }
}
