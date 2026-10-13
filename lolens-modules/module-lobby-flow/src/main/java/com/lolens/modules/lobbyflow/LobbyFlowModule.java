package com.lolens.modules.lobbyflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.lcu.LcuClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 返回房间 / 重赛模块：轮询 /lol-gameflow/v1/gameflow-phase，
 * 结算阶段自动 play-again（延迟防抖），Reconnect 阶段自动重连。
 *
 * <p>借鉴 LeagueAkari lobby-flow-controller：基于 gameflow.phase 状态机驱动
 * playAgain（缓冲延迟）与 reconnect（延迟 10s）；仅在阶段仍满足条件时执行，
 * 离开触发阶段自动复位防重。合规边界：仅调用官方 LCU 本地 HTTP API。</p>
 */
public class LobbyFlowModule implements LolModule {

    /** 结算阶段（触发 play-again）。 */
    private static final Set<String> SETTLEMENT_PHASES =
            Set.of("WaitingForStats", "PreEndOfGame", "EndOfGame");
    private static final String RECONNECT_PHASE = "Reconnect";
    /** 结算期防抖键：三个结算子阶段共用同一键，避免一次结算流程重复调度多个 play-again。 */
    private static final String SETTLEMENT_KEY = "settlement";

    private final long pollIntervalSeconds;
    private final long playAgainDelaySeconds;
    private final long reconnectDelaySeconds;

    private ModuleContext ctx;
    private ScheduledFuture<?> pollTask;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /** 记录各触发阶段最近一次已调度的时刻（防抖）。 */
    private final ConcurrentHashMap<String, Long> lastTriggeredAt = new ConcurrentHashMap<>();

    public LobbyFlowModule() {
        this(2, 5, 10);
    }

    public LobbyFlowModule(long pollIntervalSeconds, long playAgainDelaySeconds, long reconnectDelaySeconds) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.playAgainDelaySeconds = Math.max(0, playAgainDelaySeconds);
        this.reconnectDelaySeconds = Math.max(0, reconnectDelaySeconds);
    }

    @Override
    public String id() {
        return "lobby-flow";
    }

    @Override
    public String displayName() {
        return "返回房间/重赛";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        this.enabled.set(settings.lobbyFlowEnabled());
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

    private void poll() {
        if (!enabled.get()) {
            return;
        }
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                JsonNode node = lcu.get("/lol-gameflow/v1/gameflow-phase", JsonNode.class);
                if (node == null || node.isNull()) {
                    return;
                }
                String phase = node.asText("");
                if (SETTLEMENT_PHASES.contains(phase)) {
                    handleSettlement(lcu, phase);
                } else if (RECONNECT_PHASE.equals(phase)) {
                    handleReconnect(lcu);
                } else {
                    // 离开触发阶段，复位防抖标记，允许下次再触发
                    lastTriggeredAt.clear();
                }
            } catch (Exception e) {
                ctx.logger().debug("gameflow-phase 查询失败: {}", e.getMessage());
            }
        });
    }

    /** 结算阶段：延迟 playAgainDelaySeconds 后自动返回房间（重赛）。 */
    private void handleSettlement(LcuClient lcu, String phase) {
        // 结算期共用一个防抖键：WaitingForStats/PreEndOfGame/EndOfGame 连续切换也只调度一次
        if (!shouldTrigger(SETTLEMENT_KEY, playAgainDelaySeconds)) {
            return;
        }
        ctx.scheduler().schedule(() -> {
            try {
                // 执行前复核：阶段仍处于结算才 play-again（防抖）
                if (!stillIn(SETTLEMENT_PHASES)) {
                    return;
                }
                lcu.post("/lol-lobby/v2/play-again", "", JsonNode.class);
                ctx.logger().info("已自动返回房间/重赛（触发阶段 {}）", phase);
            } catch (Exception e) {
                ctx.logger().warn("自动 play-again 失败: {}", e.getMessage());
            }
        }, playAgainDelaySeconds, TimeUnit.SECONDS);
    }

    /** Reconnect 阶段：延迟 reconnectDelaySeconds 后自动重连（避免客户端恢复未完成）。 */
    private void handleReconnect(LcuClient lcu) {
        if (!shouldTrigger(RECONNECT_PHASE, reconnectDelaySeconds)) {
            return;
        }
        ctx.scheduler().schedule(() -> {
            try {
                if (!stillIn(Set.of(RECONNECT_PHASE))) {
                    return;
                }
                lcu.post("/lol-gameflow/v1/reconnect", "", JsonNode.class);
                ctx.logger().info("已自动重连对局");
            } catch (Exception e) {
                ctx.logger().warn("自动 reconnect 失败: {}", e.getMessage());
            }
        }, reconnectDelaySeconds, TimeUnit.SECONDS);
    }

    /** 防抖：同一阶段在本次持续期间只调度一次。 */
    private boolean shouldTrigger(String phase, long delaySeconds) {
        if (delaySeconds < 0) {
            return false;
        }
        Long prev = lastTriggeredAt.putIfAbsent(phase, System.currentTimeMillis());
        return prev == null;
    }

    /** 复核当前 phase 是否仍属于给定集合。 */
    private boolean stillIn(Set<String> phases) {
        try {
            JsonNode node = ctx.services().find(LcuClient.class)
                    .map(lcu -> lcu.get("/lol-gameflow/v1/gameflow-phase", JsonNode.class))
                    .orElse(null);
            return node != null && !node.isNull() && phases.contains(node.asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onUnload() {
        // 无需额外释放
    }
}
