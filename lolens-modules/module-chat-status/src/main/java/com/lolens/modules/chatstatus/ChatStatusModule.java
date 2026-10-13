package com.lolens.modules.chatstatus;

import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.kernel.event.ConnectionStateChanged;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.lcu.LcuClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在线状态自定义模块：连接 LCU 后通过 PUT /lol-chat/v1/me 自定义
 * availability / statusMessage / 段位展示（纯聊天显示，不影响真实段位）。
 *
 * <p>借鉴 LeagueAkari login-automation/status：请求体
 * {availability, statusMessage, lol:{rankedLeagueQueue, rankedLeagueTier, rankedLeagueDivision}}。
 * 合规边界：仅调用官方 LCU 本地 HTTP API，不读写游戏内存。</p>
 */
public class ChatStatusModule implements LolModule {

    private final String availability;
    private final String statusMessage;
    private final String rankedQueue;
    private final String rankedTier;
    private final String rankedDivision;

    private ModuleContext ctx;
    private Subscription connSub;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    /** 失败重试：写入失败后退避重试（连接后客户端可能尚未登录完成）。 */
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_SECONDS = 10;
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final AtomicBoolean retryScheduled = new AtomicBoolean(false);
    private ScheduledFuture<?> retryTask;

    public ChatStatusModule() {
        this("chat", "", "", "", "");
    }

    public ChatStatusModule(String availability, String statusMessage,
                            String rankedQueue, String rankedTier, String rankedDivision) {
        this.availability = availability == null || availability.isBlank() ? "chat" : availability;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.rankedQueue = rankedQueue == null ? "" : rankedQueue;
        this.rankedTier = rankedTier == null ? "" : rankedTier;
        this.rankedDivision = rankedDivision == null ? "" : rankedDivision;
    }

    @Override
    public String id() {
        return "chat-status";
    }

    @Override
    public String displayName() {
        return "在线状态自定义";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        this.enabled.set(settings.chatStatusEnabled());
        connSub = ctx.events().subscribe(ConnectionStateChanged.class, this::onConnectionChanged);
    }

    @Override
    public void onEnable() {
        // 模块 onLoad 时 LCU 可能已 CONNECTED（GameDataProvider 先于模块启动连接轮询），
        // 此时 ConnectionStateChanged 事件早已发过，主动补查一次状态
        ctx.services().find(LcuClient.class)
                .filter(lcu -> lcu.state() == ConnectionState.CONNECTED)
                .ifPresent(lcu -> applyStatus());
    }

    @Override
    public void onDisable() {
        if (retryTask != null) {
            retryTask.cancel(true);
        }
    }

    private void onConnectionChanged(ConnectionStateChanged event) {
        if (!enabled.get() || event.to() != ConnectionState.CONNECTED) {
            return;
        }
        applyStatus();
    }

    /** 向 LCU 写入自定义聊天状态（每次连接会话应用一次，失败退避重试）。 */
    private void applyStatus() {
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                Map<String, Object> lol = new HashMap<>();
                if (!rankedQueue.isEmpty()) {
                    lol.put("rankedLeagueQueue", rankedQueue);
                }
                if (!rankedTier.isEmpty()) {
                    lol.put("rankedLeagueTier", rankedTier);
                }
                if (!rankedDivision.isEmpty()) {
                    lol.put("rankedLeagueDivision", rankedDivision);
                }

                Map<String, Object> body = new HashMap<>();
                body.put("availability", availability);
                if (!statusMessage.isEmpty()) {
                    body.put("statusMessage", statusMessage);
                }
                if (!lol.isEmpty()) {
                    body.put("lol", lol);
                }
                lcu.put("/lol-chat/v1/me", body);
                retryCount.set(0);
                ctx.logger().info("已应用在线状态: availability={} statusMessage={} lol={}",
                        availability, statusMessage, lol);
            } catch (Exception e) {
                // 连接初期客户端常未登录完成，PUT 必然失败；退避重试直到成功或达到上限
                if (retryCount.getAndIncrement() < MAX_RETRIES
                        && retryScheduled.compareAndSet(false, true)) {
                    ctx.logger().warn("在线状态写入失败（{}），{} 秒后重试: {}",
                            retryCount.get(), RETRY_DELAY_SECONDS, e.getMessage());
                    retryTask = ctx.scheduler().schedule(() -> {
                        retryScheduled.set(false);
                        applyStatus();
                    }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                } else {
                    ctx.logger().warn("在线状态写入失败，放弃重试: {}", e.getMessage());
                }
            }
        });
    }

    @Override
    public void onUnload() {
        if (connSub != null) {
            connSub.close();
        }
    }
}
