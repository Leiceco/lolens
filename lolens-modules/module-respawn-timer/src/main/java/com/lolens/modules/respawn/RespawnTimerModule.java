package com.lolens.modules.respawn;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.event.GameDataUpdated;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.model.LiveGameData;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.lcu.LcuClient;
import com.lolens.services.live.LiveClientApi;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 重生倒计时模块：基于 Live Client API 检测本玩家死亡状态，
 * 在叠加窗口中央显示复活倒计时。
 *
 * <p>重生时长采用《英雄联盟》官方现行机制：基础复活等待时间（BRW，按等级查表）
 * + 时间增长因子（TIFx，按游戏分钟分段），与 LeagueAkari 参考实现口径一致。
 * 数据来源仅为官方/本地 HTTP API，不读写游戏内存。</p>
 */
public class RespawnTimerModule implements LolModule {

    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 1;

    private final long pollIntervalSeconds;

    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private RespawnTimerPanel panel;
    private ModuleContext ctx;
    private Subscription dataSub;
    private Subscription phaseSub;
    private ScheduledFuture<?> pollTask;

    /** 当前召唤师名（登录后缓存），用于定位对局中的“我方”。 */
    private volatile String cachedSelfName;
    /** 最新缓存的对局时间与本人等级（来自 GameDataUpdated，5s 粒度足够用于重生公式）。 */
    private volatile double cachedGameTimeSeconds = 0;
    private volatile int cachedLevel = 1;
    private volatile GamePhase currentPhase = GamePhase.UNKNOWN;

    /** 死亡状态机：是否已死亡、死亡时刻、本次重生总时长。 */
    private volatile boolean wasDead = false;
    private volatile long deathAtMs = 0;
    private volatile long respawnSeconds = 0;

    public RespawnTimerModule() {
        this(DEFAULT_POLL_INTERVAL_SECONDS);
    }

    public RespawnTimerModule(long pollIntervalSeconds) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
    }

    @Override
    public String id() {
        return "respawn-timer";
    }

    @Override
    public String displayName() {
        return "重生倒计时";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        this.panel = new RespawnTimerPanel();
        dataSub = ctx.events().subscribe(GameDataUpdated.class, this::onGameData);
        phaseSub = ctx.events().subscribe(GamePhaseChanged.class, this::onPhaseChanged);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.CENTER));
    }

    @Override
    public void onEnable() {
        pollTask = ctx.scheduler().scheduleAtFixedRate(this::poll, 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        if (pollTask != null) {
            pollTask.cancel(true);
        }
    }

    private void onGameData(GameDataUpdated event) {
        LiveGameData data = event.data();
        if (data == null) {
            return;
        }
        cachedGameTimeSeconds = data.gameTimeSeconds();
        LiveGameData.LivePlayer me = resolveSelf(data);
        if (me != null) {
            cachedLevel = me.level();
        }
        panel.updateHero(me == null ? null : me.championName());
    }

    private void onPhaseChanged(GamePhaseChanged event) {
        currentPhase = event.to();
        if (event.to() != GamePhase.IN_PROGRESS) {
            wasDead = false;
            panel.hideDeath();
        }
    }

    /** 每秒轮询本玩家死亡状态并刷新倒计时（调度线程）。 */
    private void poll() {
        if (currentPhase != GamePhase.IN_PROGRESS) {
            return;
        }
        ctx.services().find(LiveClientApi.class).ifPresent(live -> {
            boolean dead = live.isPlayerDead();
            if (dead) {
                if (!wasDead) {
                    wasDead = true;
                    deathAtMs = System.currentTimeMillis();
                    respawnSeconds = calcRespawnSeconds(cachedLevel, cachedGameTimeSeconds);
                    panel.showDeath(respawnSeconds);
                    ctx.logger().info("检测到死亡，重生倒计时 {} 秒 (等级 {}，游戏时间 {}s)",
                            respawnSeconds, cachedLevel, (long) cachedGameTimeSeconds);
                } else {
                    long elapsed = (System.currentTimeMillis() - deathAtMs) / 1000;
                    long remaining = Math.max(0, respawnSeconds - elapsed);
                    panel.updateCountdown(remaining);
                }
            } else {
                if (wasDead) {
                    wasDead = false;
                    panel.hideDeath();
                    ctx.logger().info("已复活");
                }
            }
        });
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
                    // displayName 为完整 Riot ID（gameName#tag），live data 中 summonerName
                    // 不含 tag，需截取 gameName；截取后为空则保持 null 下次再试。
                    int hash = display.indexOf('#');
                    String gameName = hash >= 0 ? display.substring(0, hash) : display;
                    if (!gameName.isBlank()) {
                        cachedSelfName = gameName;
                    }
                }
            } catch (Exception ignored) {
                // LCU 未就绪时保持 null，下次轮询再试
            }
        });
        return cachedSelfName;
    }

    /**
     * 计算重生总时长（秒，向上取整）。
     *
     * <p>官方现行机制：总死亡时间 = BRW + BRW × TIFx。
     * BRW 按等级查表（1~18 级 10~52.5 秒）；TIFx 从 15 分钟起按游戏分钟分段增长，55 分钟封顶 50%。</p>
     */
    static int calcRespawnSeconds(int level, double gameTimeSeconds) {
        int lvl = Math.max(1, Math.min(18, level));
        double brw = switch (lvl) {
            case 1, 2 -> 10.0;
            case 3, 4 -> 12.0;
            case 5 -> 14.0;
            case 6 -> 16.0;
            case 7 -> 20.0;
            case 8 -> 25.0;
            case 9 -> 28.0;
            case 10 -> 32.5;
            case 11 -> 35.0;
            case 12 -> 37.5;
            case 13 -> 40.0;
            case 14 -> 42.5;
            case 15 -> 45.0;
            case 16 -> 47.5;
            case 17 -> 50.0;
            default -> 52.5;
        };
        double minutes = Math.max(0, gameTimeSeconds / 60.0);
        double tifx;
        if (minutes < 15) {
            tifx = 0;
        } else if (minutes < 30) {
            tifx = Math.ceil(2 * (minutes - 15)) * 0.00425;
        } else if (minutes < 45) {
            tifx = 0.1275 + Math.ceil(2 * (minutes - 30)) * 0.0030;
        } else if (minutes < 55) {
            tifx = 0.2175 + Math.ceil(2 * (minutes - 45)) * 0.0145;
        } else {
            tifx = 0.5075; // 硬上限 50%
        }
        return (int) Math.ceil(brw + brw * tifx);
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
