package com.lolens.modules.honor;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.lcu.LcuClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自动点赞队友模块：结算（EndOfGame）阶段轮询 LCU /lol-honor/v1/ballot，
 * 为尚未获得点赞的队友自动授予荣誉。
 *
 * <p>借鉴 LeagueAkari honor-controller：依据 ballot 的 eligibleAllies / votes 选择点赞对象，
 * 优先给获得点赞数最少的队友投票（让每个队友都能收到荣誉），并提交 honor 请求。
 * 合规边界：仅调用官方 LCU 本地 HTTP API，不读写游戏内存。</p>
 */
public class HonorModule implements LolModule {

    private final long pollIntervalSeconds;
    private final boolean alliesOnly;
    private final String honorType;

    private ModuleContext ctx;
    private ScheduledFuture<?> pollTask;
    private Subscription phaseSub;
    private volatile GamePhase currentPhase = GamePhase.UNKNOWN;

    /** 由 AppSettings 控制的模块总开关。 */
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    /** 点赞完成后记录 gameId，避免同一局重复点赞。 */
    private final AtomicLong handledGameId = new AtomicLong(-1);
    /** 当前是否正在执行点赞（防止多轮轮询并发重复点赞）。 */
    private final AtomicBoolean voting = new AtomicBoolean(false);

    public HonorModule() {
        this(3, true, "MVP");
    }

    public HonorModule(long pollIntervalSeconds, boolean alliesOnly, String honorType) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.alliesOnly = alliesOnly;
        this.honorType = honorType == null || honorType.isBlank() ? "MVP" : honorType;
    }

    @Override
    public String id() {
        return "honor";
    }

    @Override
    public String displayName() {
        return "自动点赞队友";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        this.enabled.set(settings.honorEnabled());
        phaseSub = ctx.events().subscribe(GamePhaseChanged.class, this::onPhaseChanged);
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

    private void onPhaseChanged(GamePhaseChanged event) {
        currentPhase = event.to();
        if (event.to() != GamePhase.END_OF_GAME) {
            // 离开结算阶段：复位防重，下一局可再次点赞
            handledGameId.set(-1);
        }
    }

    private void poll() {
        // 仅在结算阶段轮询：非结算阶段 ballot 接口返回 405，每 3s 刷一次错误日志
        if (!enabled.get() || currentPhase != GamePhase.END_OF_GAME) {
            return;
        }
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                JsonNode ballot = lcu.get("/lol-honor/v1/ballot", JsonNode.class);
                if (ballot == null || ballot.isNull() || !ballot.hasNonNull("gameId")) {
                    // 不在结算阶段（ballot 不存在），复位防重以便下一局可再次点赞
                    handledGameId.set(-1);
                    return;
                }
                long gameId = ballot.path("gameId").asLong(-1);
                if (gameId < 0 || gameId == handledGameId.get()) {
                    return;
                }
                if (!voting.compareAndSet(false, true)) {
                    return; // 上一轮点赞仍在进行中
                }
                try {
                    boolean allOk = voteAll(lcu, ballot);
                    if (allOk) {
                        handledGameId.set(gameId);
                        ctx.logger().info("本局点赞完成 gameId={}", gameId);
                    } else {
                        // 全部候选点赞失败：不标记完成，下一轮轮询重试
                        ctx.logger().warn("本局点赞全部失败，下一轮重试 gameId={}", gameId);
                    }
                } finally {
                    voting.set(false);
                }
            } catch (Exception e) {
                ctx.logger().debug("ballot 查询失败: {}", e.getMessage());
            }
        });
    }

    /** 对可点赞的队友（或含敌方）逐一授予荣誉。返回是否全部成功（至少一个候选成功即视为完成）。 */
    private boolean voteAll(LcuClient lcu, JsonNode ballot) throws Exception {
        List<JsonNode> candidates = new ArrayList<>();
        JsonNode allies = ballot.path("eligibleAllies");
        if (allies.isArray()) {
            allies.forEach(candidates::add);
        }
        if (!alliesOnly) {
            JsonNode opponents = ballot.path("eligibleOpponents");
            if (opponents.isArray()) {
                opponents.forEach(candidates::add);
            }
        }
        // 借鉴 LeagueAkari：优先给获得点赞最少的玩家投票（让每位玩家都能收到荣誉）
        candidates.sort(Comparator.comparingInt(
                c -> c.path("votes").asInt(0)));

        int successCount = 0;
        int attemptCount = 0;
        for (JsonNode candidate : candidates) {
            String puuid = candidate.path("puuid").asText("");
            if (puuid.isBlank()) {
                continue;
            }
            attemptCount++;
            Map<String, Object> body = Map.of(
                    "honorType", honorType,
                    "recipientPuuid", puuid);
            try {
                lcu.post("/lol-honor/v1/honor", body, JsonNode.class);
                successCount++;
                ctx.logger().info("已向 {} 授予荣誉 ({})",
                        candidate.path("summonerName").asText(puuid), honorType);
            } catch (Exception e) {
                ctx.logger().warn("向 {} 点赞失败: {}", puuid, e.getMessage());
            }
        }
        return attemptCount == 0 || successCount > 0;
    }

    @Override
    public void onUnload() {
        if (phaseSub != null) {
            phaseSub.close();
        }
    }
}
