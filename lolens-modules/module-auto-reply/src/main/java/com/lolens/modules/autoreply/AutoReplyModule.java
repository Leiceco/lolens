package com.lolens.modules.autoreply;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.lcu.LcuClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动回复模块：轮询 LCU 聊天会话新消息，命中预设规则自动回复。
 *
 * <p>借鉴 LeagueAkari auto-reply-controller：监听聊天消息并按规则回复；忽略自己发送的消息，
 * 同一消息只回复一次。合规边界：仅调用官方 LCU 本地 HTTP API，不读写游戏内存。</p>
 */
public class AutoReplyModule implements LolModule {

    /** 规则：触发词包含匹配 + 回复文本。 */
    public record ReplyRule(String trigger, String response) {
    }

    private static final int MAX_SCAN_MESSAGES = 30;

    private final long pollIntervalSeconds;
    private final List<ReplyRule> rules;

    private ModuleContext ctx;
    private ScheduledFuture<?> pollTask;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /** 每个会话已处理到的最大消息 id（防重复回复）。 */
    private final Map<String, Long> lastSeenId = new ConcurrentHashMap<>();
    /** 已回复过的消息（key=会话id:消息id，区分会话防冲突）。 */
    private final Set<String> repliedMessageIds = ConcurrentHashMap.newKeySet();
    /** 发送失败待重试的消息（key=会话id:消息id），避免失败消息被 lastSeenId 永久跳过。 */
    private final Set<String> retryMessageIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_RETRY_IDS = 200;
    private final AtomicReference<String> mySummonerId = new AtomicReference<>("");

    public AutoReplyModule() {
        this(2, List.of());
    }

    public AutoReplyModule(long pollIntervalSeconds, List<ReplyRule> rules) {
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    @Override
    public String id() {
        return "auto-reply";
    }

    @Override
    public String displayName() {
        return "自动回复";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.ctx = ctx;
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        this.enabled.set(settings.autoReplyEnabled());
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
        if (!enabled.get() || rules.isEmpty()) {
            return;
        }
        ctx.services().find(LcuClient.class).ifPresent(lcu -> {
            try {
                resolveMySummonerId(lcu);
                // 尚未解析出自己的 summonerId 时本轮跳过：避免无法过滤自己的消息导致自我回复循环
                if (mySummonerId.get().isEmpty()) {
                    return;
                }
                JsonNode conversations = lcu.get("/lol-chat/v1/conversations", JsonNode.class);
                if (conversations == null || !conversations.isArray()) {
                    return;
                }
                for (JsonNode conv : conversations) {
                    String convId = conv.path("id").asText("");
                    String convType = conv.path("type").asText("");
                    if (convId.isBlank() || !("chat".equals(convType) || "group".equals(convType))) {
                        continue;
                    }
                    scanConversation(lcu, convId);
                }
            } catch (Exception e) {
                ctx.logger().debug("自动回复轮询失败: {}", e.getMessage());
            }
        });
    }

    private void resolveMySummonerId(LcuClient lcu) {
        if (!mySummonerId.get().isEmpty()) {
            return;
        }
        try {
            JsonNode me = lcu.get("/lol-summoner/v1/current-summoner", JsonNode.class);
            if (me != null && me.hasNonNull("summonerId")) {
                mySummonerId.set(me.path("summonerId").asText(""));
            }
        } catch (Exception e) {
            // 稍后重试
        }
    }

    private void scanConversation(LcuClient lcu, String convId) {
        try {
            JsonNode messages = lcu.get("/lol-chat/v1/conversations/" + convId + "/messages",
                    JsonNode.class);
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return;
            }
            long maxId = 0;
            int size = messages.size();
            int from = Math.max(0, size - MAX_SCAN_MESSAGES);
            for (int i = from; i < size; i++) {
                maxId = Math.max(maxId, messages.get(i).path("id").asLong(0));
            }
            Long seen = lastSeenId.getOrDefault(convId, 0L);
            if (seen == 0L) {
                // 首次扫描：直接把进度推进到当前最大 id，不回放历史消息（避免回复几小时前的旧消息）
                if (maxId > 0) {
                    lastSeenId.put(convId, maxId);
                }
                return;
            }
            List<JsonNode> pending = new ArrayList<>();
            for (int i = from; i < size; i++) {
                JsonNode msg = messages.get(i);
                long id = msg.path("id").asLong(0);
                String msgKey = convId + ":" + id;
                if (id > seen || retryMessageIds.contains(msgKey)) {
                    pending.add(msg);
                }
            }
            // 逐条处理后统一推进进度：失败的消息进入 retry 集合，下一轮重试，不会被静默丢弃
            for (JsonNode msg : pending) {
                handleMessage(lcu, convId, msg);
            }
            if (maxId > 0) {
                lastSeenId.put(convId, maxId);
            }
        } catch (Exception e) {
            ctx.logger().debug("扫描会话 {} 失败: {}", convId, e.getMessage());
        }
    }

    private void handleMessage(LcuClient lcu, String convId, JsonNode msg) {
        String msgId = msg.path("id").asText("");
        String fromId = msg.path("fromId").asText("");
        String type = msg.path("type").asText("");
        String body = msg.path("body").asText("");

        // 忽略自己发送的消息与系统/事件消息
        if (fromId.isEmpty() || fromId.equals(mySummonerId.get())
                || "self".equals(type) || "system".equals(type)) {
            return;
        }
        String msgKey = convId + ":" + msgId;
        if (msgId.isEmpty() || repliedMessageIds.contains(msgKey)) {
            return;
        }
        String response = matchResponse(body);
        if (response == null) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of("body", response, "type", "chat");
            lcu.post("/lol-chat/v1/conversations/" + convId + "/messages", payload, JsonNode.class);
            repliedMessageIds.add(msgKey);
            retryMessageIds.remove(msgKey);
            ctx.logger().info("自动回复: [{}] -> {}", body, response);
        } catch (Exception e) {
            // 发送失败：进入重试集合，下一轮扫描重试（集合设上限防止无界增长）
            if (retryMessageIds.size() < MAX_RETRY_IDS) {
                retryMessageIds.add(msgKey);
            }
            ctx.logger().warn("自动回复发送失败: {}", e.getMessage());
        }
    }

    private String matchResponse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (ReplyRule rule : rules) {
            if (body.contains(rule.trigger())) {
                return rule.response();
            }
        }
        return null;
    }

    @Override
    public void onUnload() {
        // 无需额外释放
    }
}
