package com.lolens.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lolens.kernel.event.ChampSelectUpdated;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.kernel.event.ConnectionStateChanged;
import com.lolens.kernel.event.EventBus;
import com.lolens.kernel.event.GameDataUpdated;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.model.ChampSelectSession;
import com.lolens.kernel.model.LiveGameData;
import com.lolens.kernel.scheduler.Scheduler;
import com.lolens.services.lcu.LcuClient;
import com.lolens.services.live.LiveClientApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据采集协调器：把数据层服务与内核事件总线串起来，是功能生效的“最后一公里”。
 *
 * <p>职责：连接 LCU → 轮询 gameflow 阶段驱动状态机 → 选人阶段轮询 champ-select
 * 发布 {@link ChampSelectUpdated} → 对局阶段轮询 Live Client Data 发布
 * {@link GameDataUpdated}。所有模块通过订阅这些事件工作，本类不感知任何具体模块。</p>
 */
public class GameDataProvider {

    private static final Logger log = LoggerFactory.getLogger(GameDataProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventBus events;
    private final LcuClient lcu;
    private final LiveClientApi live;
    private final Scheduler scheduler;
    private final int livePollIntervalSeconds;

    private final AtomicReference<GamePhase> currentPhase = new AtomicReference<>(GamePhase.UNKNOWN);
    private final AtomicReference<ConnectionState> currentConnection =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    /** summonerId → displayName 缓存，避免选人阶段反复请求。 */
    private final Map<Long, String> summonerNameCache = new ConcurrentHashMap<>();

    private ScheduledFuture<?> phaseTask;
    private ScheduledFuture<?> liveTask;
    private volatile boolean stopped = false;

    public GameDataProvider(EventBus events, LcuClient lcu, LiveClientApi live,
                            Scheduler scheduler, int livePollIntervalSeconds) {
        this.events = events;
        this.lcu = lcu;
        this.live = live;
        this.scheduler = scheduler;
        this.livePollIntervalSeconds = Math.max(1, livePollIntervalSeconds);
    }

    /** 启动采集：连接 LCU 并开始周期轮询。 */
    public void start() {
        lcu.onStateChanged(this::onConnectionChanged);
        lcu.connect();
        // 固定延迟调度：单次请求超时不会引发 scheduleAtFixedRate 的背靠背补跑风暴
        phaseTask = scheduler.scheduleWithFixedDelay(this::pollPhase, 1, 2, TimeUnit.SECONDS);
        liveTask = scheduler.scheduleWithFixedDelay(
                this::pollLiveData, 2, livePollIntervalSeconds, TimeUnit.SECONDS);
        log.info("数据采集器已启动（阶段轮询 2s / 对局轮询 {}s）", livePollIntervalSeconds);
    }

    /** 停止采集：取消轮询并断开 LCU 连接线程，此后不再向事件总线发布。 */
    public void stop() {
        stopped = true;
        if (phaseTask != null) {
            phaseTask.cancel(true);
        }
        if (liveTask != null) {
            liveTask.cancel(true);
        }
        try {
            lcu.disconnect();
        } catch (Exception e) {
            log.debug("断开 LCU 失败: {}", e.getMessage());
        }
    }

    private void onConnectionChanged(ConnectionState state) {
        if (stopped) {
            return;
        }
        ConnectionState prev = currentConnection.getAndSet(state);
        if (prev != state) {
            events.publish(new ConnectionStateChanged(prev, state));
        }
    }

    private void pollPhase() {
        if (stopped) {
            return;
        }
        GamePhase phase = detectPhase();
        GamePhase prev = currentPhase.getAndSet(phase);
        if (prev != phase) {
            events.publish(new GamePhaseChanged(prev, phase));
        }
        if (phase == GamePhase.CHAMP_SELECT) {
            pollChampSelect();
        }
    }

    private GamePhase detectPhase() {
        try {
            // gameflow-phase 端点始终可用（未登录时返回 "None"）；
            // /lol-gameflow/v1/session 在客户端未登录时 404，导致每 2s 一条错误日志
            String phase = lcu.get("/lol-gameflow/v1/gameflow-phase", String.class);
            return mapPhase(phase == null ? "" : phase.trim());
        } catch (Exception e) {
            // LCU 未连接或客户端未就绪
            return GamePhase.UNKNOWN;
        }
    }

    private GamePhase mapPhase(String phase) {
        return switch (phase) {
            case "Lobby" -> GamePhase.LOBBY;
            case "Matchmaking" -> GamePhase.MATCHMAKING;
            case "ReadyCheck" -> GamePhase.LOBBY;
            case "ChampSelect" -> GamePhase.CHAMP_SELECT;
            case "GameStart" -> GamePhase.LOADING;
            case "InProgress" -> GamePhase.IN_PROGRESS;
            // 断线重连：对局实际仍在进行，按进行中处理，保证对局数据持续采集与面板可见
            case "Reconnect" -> GamePhase.IN_PROGRESS;
            case "WaitingForStats", "PreEndOfGame", "EndOfGame" -> GamePhase.END_OF_GAME;
            case "None" -> GamePhase.NONE;
            default -> GamePhase.UNKNOWN;
        };
    }

    private void pollChampSelect() {
        try {
            JsonNode session = lcu.get("/lol-champ-select/v1/session", JsonNode.class);
            if (session == null || session.isNull() || session.isMissingNode()) {
                return;
            }
            int localCellId = session.path("localPlayerCellId").asInt(-1);
            String myTeamLabel = resolveTeamLabel(session, localCellId);
            String theirTeamLabel = "ORDER".equals(myTeamLabel) ? "CHAOS" : "ORDER";
            List<ChampSelectSession.ChampSelectPlayer> myTeam =
                    parseTeam(session.path("myTeam"), myTeamLabel);
            List<ChampSelectSession.ChampSelectPlayer> theirTeam =
                    parseTeam(session.path("theirTeam"), theirTeamLabel);

            Integer myChampionId = null;
            String myPosition = "";
            for (JsonNode p : session.path("myTeam")) {
                if (p.path("cellId").asInt(-1) == localCellId) {
                    int champ = p.path("championId").asInt(0);
                    myChampionId = champ > 0 ? champ : null;
                    myPosition = p.path("assignedPosition").asText("");
                    break;
                }
            }
            String chatRoom = session.path("chatDetails").path("chatRoomName").asText("");
            long sessionId = chatRoom.isEmpty() ? 0L : chatRoom.hashCode();
            events.publish(new ChampSelectUpdated(
                    new ChampSelectSession(sessionId, myTeam, theirTeam, myChampionId, myPosition)));
        } catch (Exception e) {
            log.debug("champ-select 轮询失败: {}", e.getMessage());
        }
    }

    /** 本队颜色标签：优先取玩家对象的 team 字段（1=ORDER 蓝方，2=CHAOS 红方），缺失时按 cellId 约定兜底。 */
    private String resolveTeamLabel(JsonNode session, int localCellId) {
        for (JsonNode p : session.path("myTeam")) {
            if (p.path("cellId").asInt(-1) == localCellId) {
                int team = p.path("team").asInt(0);
                if (team == 1) {
                    return "ORDER";
                }
                if (team == 2) {
                    return "CHAOS";
                }
            }
        }
        return localCellId >= 0 && localCellId < 5 ? "ORDER" : "CHAOS";
    }

    private List<ChampSelectSession.ChampSelectPlayer> parseTeam(JsonNode teamNode, String teamLabel) {
        List<ChampSelectSession.ChampSelectPlayer> result = new ArrayList<>();
        if (teamNode == null || !teamNode.isArray()) {
            return result;
        }
        for (JsonNode p : teamNode) {
            int summonerId = p.path("summonerId").asInt(-1);
            int champId = p.path("championId").asInt(0);
            int pickIntent = p.path("championPickIntent").asInt(0);
            result.add(new ChampSelectSession.ChampSelectPlayer(
                    resolveSummonerName(summonerId),
                    champId > 0 ? champId : null,
                    pickIntent > 0 ? pickIntent : null,
                    p.path("assignedPosition").asText(""),
                    teamLabel));
        }
        return result;
    }

    private String resolveSummonerName(int summonerId) {
        if (summonerId <= 0) {
            return "";
        }
        Long key = (long) summonerId;
        String cached = summonerNameCache.get(key);
        if (cached != null) {
            return cached;
        }
        String fetched = fetchSummonerName(key);
        // 仅缓存成功结果：失败值（纯数字 id）不再进入缓存，下一轮事件会重试查询
        if (!fetched.isBlank()) {
            summonerNameCache.put(key, fetched);
            return fetched;
        }
        return String.valueOf(summonerId);
    }

    private String fetchSummonerName(Long summonerId) {
        try {
            JsonNode s = lcu.get("/lol-summoner/v1/summoners/" + summonerId, JsonNode.class);
            String name = s == null ? "" : s.path("displayName").asText("");
            return name.isBlank() ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private void pollLiveData() {
        if (stopped || currentPhase.get() != GamePhase.IN_PROGRESS) {
            return;
        }
        try {
            LiveGameData data = live.getAllGameData();
            if (data != null) {
                events.publish(new GameDataUpdated(data));
            }
        } catch (Exception e) {
            log.debug("Live Client Data 轮询失败: {}", e.getMessage());
        }
    }
}
