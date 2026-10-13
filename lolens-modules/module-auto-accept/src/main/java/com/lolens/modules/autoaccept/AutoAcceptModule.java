package com.lolens.modules.autoaccept;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.lcu.LcuClient;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 自动接受对局模块：轮询 LCU /lol-matchmaking/v1/ready-check，
 * 检测到匹配就绪（ReadyCheck）时通过 accept 接口自动接受。
 *
 * <p>借鉴 LeagueAkari auto-accept-controller：支持延迟接受（等待 delaySeconds 后再次
 * 确认 ReadyCheck 仍有效再接受，避免误点）、防抖与离开阶段自动取消未执行的接受。
 * 合规边界：仅调用官方 LCU 本地 HTTP API，不读写游戏内存。</p>
 */
public class AutoAcceptModule implements LolModule {

    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 1;

    private final long pollIntervalSeconds;
    private final long acceptDelaySeconds;

    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private AutoAcceptPanel panel;
    private ModuleContext ctx;
    private Subscription phaseSub;
    private ScheduledFuture<?> pollTask;

    /** 已调度但尚未执行的延迟接受任务（延迟模式下用于取消）。 */
    private ScheduledFuture<?> pendingAcceptTask;

    /** 自动接受开关（设置页可配，重启生效）。 */
    private volatile boolean enabled = true;
    /** 同一轮 ReadyCheck 只自动接受一次，避免重复调用。 */
    private volatile boolean handled = false;
    private volatile GamePhase currentPhase = GamePhase.UNKNOWN;

    /** 防重/延迟任务状态锁：handled 与 pendingAcceptTask 由事件总线线程与调度线程并发读写，需同步保护。 */
    private final Object acceptLock = new Object();

    public AutoAcceptModule() {
        this(DEFAULT_POLL_INTERVAL_SECONDS, 0);
    }

    public AutoAcceptModule(long pollIntervalSeconds, long acceptDelaySeconds) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.acceptDelaySeconds = Math.max(0, acceptDelaySeconds);
    }

    @Override
    public String id() {
        return "auto-accept";
    }

    @Override
    public String displayName() {
        return "自动接受对局";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        this.enabled = settings.autoAcceptEnabled();
        this.panel = new AutoAcceptPanel(enabled);
        phaseSub = ctx.events().subscribe(GamePhaseChanged.class, this::onPhaseChanged);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.CENTER));
    }

    @Override
    public void onEnable() {
        pollTask = ctx.scheduler().scheduleAtFixedRate(this::poll, 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        cancelPendingAccept();
        if (pollTask != null) {
            pollTask.cancel(true);
        }
    }

    private void onPhaseChanged(GamePhaseChanged event) {
        currentPhase = event.to();
        // 离开匹配相关阶段时复位，保证下一次 ReadyCheck 仍会触发自动接受
        if (event.to() != GamePhase.LOBBY && event.to() != GamePhase.MATCHMAKING) {
            resetReadyState();
            panel.reset();
        }
    }

    /** 轮询 ready-check 状态并驱动自动接受（调度线程）。 */
    private void poll() {
        // 面板仅在 LOBBY / MATCHMAKING 阶段可见，但轮询保持轻量（LCU 未就绪时异常被吞掉）
        if (!enabled) {
            panel.showDisabled();
            return;
        }
        if (currentPhase != GamePhase.LOBBY && currentPhase != GamePhase.MATCHMAKING) {
            return;
        }
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                JsonNode rc = lcu.get("/lol-matchmaking/v1/ready-check", JsonNode.class);
                if (rc == null) {
                    panel.showIdle();
                    resetReadyState();
                    return;
                }
                String state = rc.path("state").asText("Invalid");
                switch (state) {
                    case "ReadyCheck" -> handleReadyCheck(lcu);
                    case "Searching" -> {
                        // 延迟接受等待期间搜索状态被取消（例如有人拒绝/掉线），取消防抖
                        resetReadyState();
                        panel.showSearching();
                    }
                    case "InProgress" -> {
                        // 已进入匹配中：取消未执行的延迟接受，避免误接受
                        cancelPendingAccept();
                        panel.showInProgress();
                    }
                    default -> {
                        resetReadyState();
                        panel.showIdle();
                    }
                }
            } catch (Exception e) {
                // LCU 未连接或请求失败（如无活动 ready-check 时 404）：复位状态并回到空闲展示
                resetReadyState();
                panel.showIdle();
                ctx.logger().debug("查询 ready-check 失败: {}", e.getMessage());
            }
        });
    }

    /** 复位延迟接受相关的防抖状态。 */
    private void resetReadyState() {
        synchronized (acceptLock) {
            handled = false;
        }
        cancelPendingAccept();
    }

    private void cancelPendingAccept() {
        synchronized (acceptLock) {
            if (pendingAcceptTask != null) {
                pendingAcceptTask.cancel(true);
                pendingAcceptTask = null;
            }
        }
    }

    /** 匹配就绪：调度接受（延迟模式等待后复核；立即模式直接接受）。 */
    private void handleReadyCheck(LcuClient lcu) {
        panel.showReadyCheck();
        boolean shouldAcceptNow;
        boolean shouldScheduleDelayed;
        synchronized (acceptLock) {
            if (handled) {
                return; // 已有延迟任务在等待或已处理
            }
            handled = true;
            shouldAcceptNow = acceptDelaySeconds <= 0;
            shouldScheduleDelayed = acceptDelaySeconds > 0;
        }
        if (shouldAcceptNow) {
            acceptNow(lcu);
        } else if (shouldScheduleDelayed) {
            scheduleDelayedAccept(lcu);
        }
    }

    /** 延迟接受：等待 delaySeconds 后复核 ready-check 仍为 ReadyCheck 再接受。 */
    private void scheduleDelayedAccept(LcuClient lcu) {
        cancelPendingAccept();
        ScheduledFuture<?> task = ctx.scheduler().schedule(() -> {
            try {
                JsonNode rc = lcu.get("/lol-matchmaking/v1/ready-check", JsonNode.class);
                String state = rc == null ? "Invalid" : rc.path("state").asText("Invalid");
                if (!"ReadyCheck".equals(state)) {
                    // 等待期间匹配状态已变化，放弃本次接受（不误点）
                    synchronized (acceptLock) {
                        handled = false;
                    }
                    panel.showIdle();
                    ctx.logger().debug("延迟接受取消：ready-check 状态变为 {}", state);
                    return;
                }
                acceptNow(lcu);
            } catch (Exception e) {
                synchronized (acceptLock) {
                    handled = false;
                }
                ctx.logger().debug("延迟接受复核失败: {}", e.getMessage());
            }
        }, acceptDelaySeconds, TimeUnit.SECONDS);
        synchronized (acceptLock) {
            pendingAcceptTask = task;
        }
        ctx.logger().info("检测到 ReadyCheck，{} 秒后自动接受", acceptDelaySeconds);
    }

    /** 执行接受（POST /lol-matchmaking/v1/ready-check/accept）。 */
    private void acceptNow(LcuClient lcu) {
        try {
            lcu.post("/lol-matchmaking/v1/ready-check/accept", "", JsonNode.class);
            panel.showAccepted();
            ctx.logger().info("已自动接受对局");
        } catch (Exception e) {
            // 失败后复位防重标记：下一轮 ReadyCheck 仍会重试（就绪窗口内无退避需求，LCU 拒绝非就绪状态的 accept 会被 catch）
            synchronized (acceptLock) {
                handled = false;
            }
            panel.showAcceptFailed(e.getMessage());
            ctx.logger().warn("自动接受失败: {}", e.getMessage());
        }
    }

    @Override
    public void onUnload() {
        if (phaseSub != null) {
            phaseSub.close();
        }
    }
}
