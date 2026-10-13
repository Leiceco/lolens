package com.lolens.modules.pickban;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.lcu.LcuClient;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 自动选人/禁用模块：匹配到对局进入选人阶段（CHAMP_SELECT）后，
 * 按预设英雄列表通过 LCU /lol-champ-select/v1/ 接口自动选择/禁用，
 * 支持普通选人位与禁用位，同一 action 只执行一次避免重复。
 *
 * <p>合规边界：仅调用官方 LCU 本地 HTTP API，不读写游戏内存、不注入。</p>
 */
public class PickBanModule implements LolModule {

    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 2;

    private final long pollIntervalSeconds;
    private final List<Integer> pickChampionIds;
    private final List<Integer> banChampionIds;

    private ModuleContext ctx;
    private PickBanPanel panel;
    private Subscription phaseSub;
    private ScheduledFuture<?> pollTask;

    /** 已处理过的 action id（同一选人流程内不重复执行）。 */
    private final Set<Long> handledActions = ConcurrentHashMap.newKeySet();
    private volatile GamePhase currentPhase = GamePhase.UNKNOWN;

    public PickBanModule() {
        this(DEFAULT_POLL_INTERVAL_SECONDS, List.of(), List.of());
    }

    public PickBanModule(long pollIntervalSeconds, List<Integer> pickChampionIds, List<Integer> banChampionIds) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.pickChampionIds = pickChampionIds == null ? List.of() : List.copyOf(pickChampionIds);
        this.banChampionIds = banChampionIds == null ? List.of() : List.copyOf(banChampionIds);
    }

    @Override
    public String id() {
        return "pick-ban";
    }

    @Override
    public String displayName() {
        return "自动选人/禁用";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        this.panel = new PickBanPanel(pickChampionIds, banChampionIds);
        phaseSub = ctx.events().subscribe(GamePhaseChanged.class, this::onPhaseChanged);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.RIGHT_TOP));
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
        if (event.to() != GamePhase.CHAMP_SELECT) {
            handledActions.clear();
            // usedChampions 也必须随选人流程结束清空，否则预设英雄被永久拉黑，
            // 第二局起自动选/禁静默失效
            usedChampions.clear();
            panel.reset();
        }
    }

    /** 轮询选人会话并执行自动选/禁（调度线程）。 */
    private void poll() {
        if (currentPhase != GamePhase.CHAMP_SELECT) {
            return;
        }
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                JsonNode session = lcu.get("/lol-champ-select/v1/session", JsonNode.class);
                if (session == null || session.isMissingNode() || !session.has("actions")) {
                    panel.showWaiting();
                    return;
                }
                int myCell = session.path("localPlayerCellId").asInt(-1);
                if (myCell < 0) {
                    panel.showWaiting();
                    return;
                }
                boolean anyPending = false;
                for (JsonNode group : session.path("actions")) {
                    for (JsonNode action : group) {
                        long actionId = action.path("id").asLong(-1);
                        boolean inProgress = action.path("isInProgress").asBoolean(false);
                        // LCU 字段为 actorCellId（单数 int），原实现读 actorCellIds 数组恒不命中
                        boolean isMine = action.path("actorCellId").asInt(-1) == myCell;
                        if (!inProgress || !isMine) {
                            continue;
                        }
                        anyPending = true;
                        String type = action.path("type").asText("");
                        int currentChampion = action.path("championId").asInt(0);
                        if ("ban".equals(type)) {
                            handleAction(lcu, actionId, currentChampion, banChampionIds, true);
                        } else if ("pick".equals(type)) {
                            handleAction(lcu, actionId, currentChampion, pickChampionIds, false);
                        }
                    }
                }
                if (!anyPending) {
                    panel.showAllHandled();
                }
            } catch (Exception e) {
                // LCU 未连接或请求失败：保持等待状态
                ctx.logger().debug("查询选人会话失败: {}", e.getMessage());
            }
        });
    }

    private void handleAction(LcuClient lcu, long actionId, int currentChampion,
                              List<Integer> candidates, boolean isBan) {
        if (currentChampion != 0 || handledActions.contains(actionId)) {
            return;
        }
        // 从预设列表选一个尚未被本场选/禁用过的英雄
        Integer target = candidates.stream()
                .filter(id -> id != null && id > 0)
                .filter(id -> !usedChampions.contains(id))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }
        try {
            // actions/{id} 的 PATCH 请求体为单个 action 对象（与 LCU 协议一致）
            lcu.patch("/lol-champ-select/v1/session/actions/" + actionId,
                    "{\"championId\":" + target + "}", JsonNode.class);
            handledActions.add(actionId);
            // 仅在写入成功后占用候选，失败时下一轮可重试同一英雄
            usedChampions.add(target);
            String name = championName(target);
            if (isBan) {
                panel.showBanned(name);
                ctx.logger().info("已自动禁用英雄: {} (action {})", name, actionId);
            } else {
                panel.showPicked(name);
                ctx.logger().info("已自动选择英雄: {} (action {})", name, actionId);
            }
        } catch (Exception e) {
            panel.showFailed(isBan ? "禁用" : "选择", e.getMessage());
            ctx.logger().warn("自动{}英雄失败: {}", isBan ? "禁用" : "选择", e.getMessage());
        }
    }

    /** 本场已使用过的英雄（避免重复选/禁）。 */
    private final Set<Integer> usedChampions = ConcurrentHashMap.newKeySet();

    private String championName(int championId) {
        String[] name = {String.valueOf(championId)};
        ctx.services().find(DataDragonService.class).ifPresent(dd ->
                dd.champion(championId).ifPresent(c -> name[0] = c.name()));
        return name[0];
    }

    @Override
    public void onUnload() {
        if (phaseSub != null) {
            phaseSub.close();
        }
    }
}
