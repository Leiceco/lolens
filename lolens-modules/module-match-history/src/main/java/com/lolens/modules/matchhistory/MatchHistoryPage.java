package com.lolens.modules.matchhistory;

import com.fasterxml.jackson.databind.JsonNode;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.lcu.LcuClient;
import com.lolens.ui.theme.Palette;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 战绩查询主窗口页面：LeagueAkari 风格三栏式战绩界面。
 *
 * <p>页面自身采用「左中数据面板 + 右侧对局卡片流」两栏，配合主框架左侧导航与右侧
 * 状态栏形成完整三栏布局：数据面板展示召唤师信息/段位/KPI（场次、胜率、KD）、
 * 模式筛选与常用英雄列表；右侧为对局记录垂直卡片流，胜利/失败以青蓝/洋红着色，
 * 点击卡片展开符文+出装图标序列与双方玩家列表。数据来源仅限 LCU 本地官方 HTTP API。</p>
 */
public class MatchHistoryPage {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 胜利（青蓝）与失败（洋红）着色：与截图 LeagueAkari 状态色一致。 */
    private static final String WIN_COLOR = "#26c6da";
    private static final String LOSS_COLOR = "#e91e8c";

    private final ModuleContext ctx;
    private final int queryCount;

    private final HBox root = new HBox(14);
    private final TextField summonerField = new TextField();
    private final Button queryButton = new Button("查询战绩");
    private final Label statusLabel = new Label("");
    private final ComboBox<String> modeFilter = new ComboBox<>();
    private final VBox cardList = new VBox(10);
    private final ScrollPane cardScroll = new ScrollPane(cardList);

    // 左中数据面板
    private final VBox statsPanel = new VBox(12);
    private final Label summonerNameLabel = new Label("未登录");
    private final Label rankLabel = new Label("未定级");
    private final Label totalGamesLabel = new Label("0");
    private final Label winRateLabel = new Label("0%");
    private final Label kdLabel = new Label("0.0");
    private final VBox heroStatsBox = new VBox(6);

    /** 当前查询的召唤师 puuid（用于详情接口）。 */
    private volatile String currentPuuid = "";
    /** 当前登录账号的 puuid（查询本人战绩时走 current-summoner 端点）。 */
    private volatile String selfPuuid = "";
    /** 英雄名缓存（Data Dragon championId → 名称）：后台线程写入、FX 线程读取，用并发容器防数据竞争。 */
    private final Map<Integer, String> championNames = new ConcurrentHashMap<>();
    /** 最近一次全部对局（供模式筛选使用）。 */
    private final List<GameRow> allGames = new ArrayList<>();

    private final DataDragonService dd;

    /** 查询防抖：避免用户连按查询/回车导致高频重复请求刷日志。 */
    private final AtomicBoolean querying = new AtomicBoolean(false);
    /** 自动加载触发防抖：仅允许发起一次自动加载，避免重复注册/重复查询。 */
    private final AtomicBoolean autoLoadTriggered = new AtomicBoolean(false);
    /** 自动加载执行完成标记：LCU 连接就绪后仅执行一次自动查询。 */
    private final AtomicBoolean autoLoadExecuted = new AtomicBoolean(false);
    /** 等待 LCU 连接就绪的监听器（页面创建后注册一次）。 */
    private final Consumer<ConnectionState> lcuStateListener = this::onLcuStateChanged;

    public MatchHistoryPage(ModuleContext ctx, boolean enabled, int queryCount) {
        this.ctx = ctx;
        this.queryCount = Math.max(1, Math.min(100, queryCount));
        this.dd = ctx.services().find(DataDragonService.class).orElse(null);
        build();
        if (enabled) {
            autoLoadCurrentSummoner();
        }
    }

    public Node node() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: " + Palette.WINDOW_BG + ";");
        root.getStyleClass().add("match-page");

        buildStatsPanel();

        VBox right = new VBox(10);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox searchRow = new HBox(10);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        summonerField.setPromptText("输入 Riot ID（名称#数字ID）或 PUUID，留空查询当前账号");
        summonerField.setPrefWidth(300);
        summonerField.getStyleClass().add("text-field");
        summonerField.setOnAction(e -> query());
        queryButton.getStyleClass().add("button");
        queryButton.setOnAction(e -> query());
        // 布局加固：按钮固定最小宽高，状态标签限宽换行，避免超长文案把按钮挤出可视区域或遮挡点击
        queryButton.setMinWidth(96);
        queryButton.setPrefHeight(34);
        statusLabel.setMaxWidth(320);
        statusLabel.setWrapText(true);
        searchRow.getChildren().addAll(summonerField, queryButton, statusLabel);
        right.getChildren().add(searchRow);

        // 模式筛选
        HBox filterRow = new HBox(10);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        Label filterLabel = new Label("模式筛选");
        filterLabel.getStyleClass().add("filter-label");
        modeFilter.getItems().addAll("全部", "召唤师峡谷", "极地大乱斗", "无限火力", "斗魂竞技场", "云顶之弈");
        modeFilter.setValue("全部");
        modeFilter.getStyleClass().add("mode-filter");
        modeFilter.setOnAction(e -> applyModeFilter());
        filterRow.getChildren().addAll(filterLabel, modeFilter);
        right.getChildren().add(filterRow);

        // 卡片流（滚动容器：纵向按需、横向禁用、不抢焦点）
        cardScroll.setFitToWidth(true);
        cardScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        cardScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        cardScroll.setFocusTraversable(false);
        cardScroll.getStyleClass().add("page-scroll");
        cardList.setSpacing(10);
        VBox.setVgrow(cardScroll, Priority.ALWAYS);
        right.getChildren().add(cardScroll);

        Label tip = new Label("点击对局卡片查看符文 / 出装 / 玩家详情");
        tip.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 12px;");
        right.getChildren().add(tip);

        root.getChildren().addAll(statsPanel, right);
    }

    /** 左中数据面板：召唤师信息 / 段位 / KPI / 常用英雄。 */
    private void buildStatsPanel() {
        statsPanel.setPrefWidth(250);
        statsPanel.setMinWidth(250);
        statsPanel.getStyleClass().add("stats-panel");

        // 召唤师信息卡
        VBox summonerCard = new VBox(10);
        summonerCard.getStyleClass().add("card summoner-card");
        summonerNameLabel.getStyleClass().add("summoner-name");
        rankLabel.getStyleClass().add("rank-badge");
        VBox nameBox = new VBox(4);
        nameBox.getChildren().addAll(summonerNameLabel, rankLabel);
        HBox avatarRow = new HBox(12);
        avatarRow.setAlignment(Pos.CENTER_LEFT);
        Label avatar = new Label("L");
        avatar.getStyleClass().add("avatar-circle");
        avatarRow.getChildren().addAll(avatar, nameBox);
        summonerCard.getChildren().add(avatarRow);

        // KPI 卡
        VBox kpiCard = new VBox(10);
        kpiCard.getStyleClass().add("card kpi-card");
        Label kpiHead = new Label("对战数据");
        kpiHead.getStyleClass().add("section-title");
        HBox kpiRow = new HBox(16);
        kpiRow.setAlignment(Pos.CENTER);
        kpiRow.getChildren().addAll(
                kpiItem("总场次", totalGamesLabel),
                kpiItem("胜率", winRateLabel),
                kpiItem("KD", kdLabel));
        kpiCard.getChildren().addAll(kpiHead, kpiRow);

        // 常用英雄卡
        VBox heroCard = new VBox(10);
        heroCard.getStyleClass().add("card hero-card");
        Label heroHead = new Label("常用英雄");
        heroHead.getStyleClass().add("section-title");
        heroStatsBox.setSpacing(8);
        heroCard.getChildren().addAll(heroHead, heroStatsBox);

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        statsPanel.getChildren().addAll(summonerCard, kpiCard, heroCard, spacer);
    }

    private VBox kpiItem(String title, Label value) {
        VBox item = new VBox(2);
        item.setAlignment(Pos.CENTER);
        value.getStyleClass().add("kpi-value");
        Label t = new Label(title);
        t.getStyleClass().add("kpi-title");
        item.getChildren().addAll(value, t);
        return item;
    }

    /** 页面打开时自动填充当前登录账号并查询：等待 LCU 连接就绪，避免页面早于连接创建导致抢跑失败。 */
    private void autoLoadCurrentSummoner() {
        if (!autoLoadTriggered.compareAndSet(false, true)) {
            return; // 已触发过自动加载，避免重复注册/重复查询
        }
        LcuClient lcu = ctx.services().find(LcuClient.class).orElse(null);
        if (lcu == null) {
            ctx.logger().warn("自动加载战绩失败：LCU 服务不可用");
            setStatus("LCU 服务不可用，可手动输入召唤师名查询", false);
            return;
        }
        if (lcu.state() == ConnectionState.CONNECTED) {
            doAutoLoadQuery(lcu);
            return;
        }
        // LCU 尚未连接就绪：注册状态监听，CONNECTED 后再执行一次自动查询（防抖见 autoLoadExecuted）
        lcu.onStateChanged(lcuStateListener);
        ctx.logger().info("等待 LCU 连接就绪，连接后将自动加载当前账号战绩");
        setStatus("等待客户端连接就绪，将自动加载当前账号战绩...", false);
    }

    /** LCU 连接状态变化回调：连接就绪后执行一次自动加载（失败未置位，重连后可重试）。 */
    private void onLcuStateChanged(ConnectionState state) {
        if (state != ConnectionState.CONNECTED) {
            return;
        }
        if (!autoLoadExecuted.compareAndSet(false, true)) {
            return; // 自动加载已成功执行过，忽略重连后的重复触发
        }
        LcuClient lcu = ctx.services().find(LcuClient.class).orElse(null);
        if (lcu != null) {
            doAutoLoadQuery(lcu);
        }
    }

    /** 实际自动加载查询：读取当前召唤师并发起战绩加载。 */
    private void doAutoLoadQuery(LcuClient lcu) {
        if (!querying.compareAndSet(false, true)) {
            return; // 已有查询进行中（手动或自动），避免并发覆盖结果
        }
        setQueryingState(true);
        new Thread(() -> {
            try {
                JsonNode me = lcu.get("/lol-summoner/v1/current-summoner", JsonNode.class);
                if (me != null && me.has("puuid")) {
                    String puuid = me.path("puuid").asText("");
                    String shownName = me.path("displayName").asText("");
                    if (shownName.isBlank()) {
                        // 国服 v1 的 displayName 为空，用 gameName#tagLine 显示
                        shownName = me.path("gameName").asText("") + "#" + me.path("tagLine").asText("");
                    }
                    final String name = shownName;
                    Platform.runLater(() -> summonerField.setText(name));
                    if (!puuid.isBlank()) {
                        currentPuuid = puuid;
                        selfPuuid = puuid;
                        ctx.logger().info("自动加载命中当前召唤师 puuid={}, displayName={}，发起战绩查询", puuid, name);
                        fetchAndShow(lcu, puuid, name);
                        // 仅在成功加载后置位；失败（如连接时客户端尚未登录）保持未完成，
                        // 下次 CONNECTED 事件或手动查询可重试
                        autoLoadExecuted.set(true);
                    } else {
                        autoLoadExecuted.set(false);
                        ctx.logger().warn("自动加载当前账号无 puuid，请手动输入召唤师名查询");
                        setStatus("当前账号无 puuid，请手动输入召唤师名查询", true);
                    }
                } else {
                    autoLoadExecuted.set(false);
                    ctx.logger().warn("自动加载当前账号失败（客户端未连接？），可手动输入召唤师名查询");
                    setStatus("自动加载当前账号失败（客户端未连接？），可手动输入召唤师名查询", true);
                }
            } catch (Exception e) {
                autoLoadExecuted.set(false);
                ctx.logger().warn("自动加载当前账号战绩失败: {}", e.getMessage(), e);
                setStatus("自动加载当前账号失败（客户端未连接？），可手动输入召唤师名查询", true);
            } finally {
                querying.set(false);
                setQueryingState(false);
            }
        }, "match-history-auto-load").start();
    }

    /** 查询进行中按钮状态：禁用按钮并显示「查询中...」，结束后恢复「查询战绩」。 */
    private void setQueryingState(boolean active) {
        Platform.runLater(() -> {
            queryButton.setDisable(active);
            queryButton.setText(active ? "查询中..." : "查询战绩");
        });
    }

    private void query() {
        if (!querying.compareAndSet(false, true)) {
            return; // 已有查询进行中，忽略连按/回车重复触发
        }
        setQueryingState(true);
        String name = summonerField.getText().trim();
        if (name.isBlank()) {
            setQueryingState(false);
            querying.set(false);
            autoLoadCurrentSummoner();
            return;
        }
        doQueryByName(name);
    }

    private void doQueryByName(String name) {
        setStatus("查询中...", false);
        new Thread(() -> {
            try {
                LcuClient lcu = ctx.services().require(LcuClient.class);
                JsonNode summoner = resolveSummoner(lcu, name);
                if (summoner == null) {
                    ctx.logger().warn("手动查询未命中召唤师 name={}（接口返回空或无 puuid）", name);
                    setStatus("未找到召唤师: " + name + "（国服需输入完整 Riot ID：名称#数字ID）", true);
                    return;
                }
                String puuid = summoner.path("puuid").asText("");
                String displayName = summoner.path("displayName").asText("");
                if (displayName.isBlank()) {
                    // 国服返回的 displayName 为空，用 gameName#tagLine 展示
                    displayName = summoner.path("gameName").asText("") + "#" + summoner.path("tagLine").asText("");
                }
                if (displayName.isBlank()) {
                    displayName = name;
                }
                currentPuuid = puuid;
                final String shownName = displayName;
                Platform.runLater(() -> summonerField.setText(shownName));
                ctx.logger().info("手动查询命中召唤师 name={} -> displayName={}, puuid={}", name, shownName, puuid);
                fetchAndShow(lcu, puuid, shownName);
            } catch (Exception e) {
                ctx.logger().warn("手动查询战绩失败 name={}: {}", name, e.getMessage(), e);
                setStatus("查询失败: " + e.getMessage(), false);
            } finally {
                querying.set(false);
                setQueryingState(false);
            }
        }, "match-history-query").start();
    }

    /**
     * 召唤师解析（国服适配）：
     * <ul>
     *   <li>含 '#' → Riot ID（名称#tag）：POST /lol-summoner/v1/summoners/aliases</li>
     *   <li>纯数字 → summonerId：GET /lol-summoner/v1/summoners/{id}</li>
     *   <li>UUID 格式（含 '-'）→ PUUID：GET /lol-summoner/v2/summoners/puuid/{puuid}</li>
     *   <li>其他 → 国服 LCU 不支持按名查询（v1 422 / v2 400），提示输入完整 Riot ID</li>
     * </ul>
     */
    private JsonNode resolveSummoner(LcuClient lcu, String input) throws Exception {
        String value = input.trim();
        int hash = value.indexOf('#');
        if (hash > 0 && hash < value.length() - 1) {
            // '#' 分支必须优先于 '-' 判断：含连字符的 Riot ID（如 Xx-Yy#CN1）不是 PUUID
            List<Map<String, String>> aliases = List.of(Map.of(
                    "gameName", value.substring(0, hash).trim(),
                    "tagLine", value.substring(hash + 1).trim()));
            JsonNode result = lcu.post("/lol-summoner/v1/summoners/aliases", aliases, JsonNode.class);
            if (result != null && result.isArray() && result.size() > 0) {
                return validSummoner(result.get(0));
            }
            return null;
        }
        if (value.matches("\\d+")) {
            return validSummoner(lcu.get("/lol-summoner/v1/summoners/" + value, JsonNode.class));
        }
        if (value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return validSummoner(lcu.get("/lol-summoner/v2/summoners/puuid/" + value, JsonNode.class));
        }
        throw new IllegalArgumentException("国服不支持按召唤师名直接查询，请输入完整 Riot ID（名称#数字ID）、PUUID 或召唤师ID");
    }

    /** 校验 summoner 返回体含非空 puuid；数组取首项；错误体/空结果返回 null。 */
    private JsonNode validSummoner(JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return null;
        }
        if (result.isArray()) {
            result = result.size() > 0 ? result.get(0) : null;
        }
        if (result == null || result.path("puuid").asText("").isBlank()) {
            return null;
        }
        return result;
    }

    private void fetchAndShow(LcuClient lcu, String puuid, String displayName) throws Exception {
        // 国服 LCU 的 /products/lol/{puuid}/matches 常返回 500（即使查询自己）；
        // 社区确认查询当前登录账号时应使用 current-summoner 路径段（见 2026 年国服 Pengu 博客）
        String accountSegment = puuid.equals(selfPuuid(lcu)) ? "current-summoner" : puuid;
        String url = "/lol-match-history/v1/products/lol/" + accountSegment
                + "/matches?begIndex=0&endIndex=" + queryCount;
        JsonNode matches = lcu.get(url, JsonNode.class);
        JsonNode games = matches == null ? null : matches.path("games").path("games");
        List<GameRow> rows = new ArrayList<>();
        if (games != null && games.isArray()) {
            for (JsonNode game : games) {
                GameRow row = toGameRow(game, puuid);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        List<GameRow> finalRows = rows;
        Platform.runLater(() -> {
            allGames.clear();
            allGames.addAll(finalRows);
            applyModeFilter();
            updateSummonerPanel(finalRows);
            if (finalRows.isEmpty()) {
                ctx.logger().warn("战绩查询返回 0 场 displayName={} puuid={}（可能接口结构变化或账号无对局记录）", displayName, puuid);
                setStatus("共 0 场对局（可能接口无数据）", true);
            } else {
                ctx.logger().info("战绩查询成功 displayName={} 共 {} 场", displayName, finalRows.size());
                setStatus("共 " + finalRows.size() + " 场对局（" + displayName + "）", false);
            }
        });
    }

    /** 当前登录账号的 puuid：查询本人战绩时用 current-summoner 端点（懒加载 + 缓存）。 */
    private String selfPuuid(LcuClient lcu) {
        String p = selfPuuid;
        if (!p.isBlank()) {
            return p;
        }
        try {
            JsonNode me = lcu.get("/lol-summoner/v1/current-summoner", JsonNode.class);
            if (me != null && me.hasNonNull("puuid")) {
                p = me.path("puuid").asText("");
                if (!p.isBlank()) {
                    selfPuuid = p;
                }
            }
        } catch (Exception ignored) {
            // 解析失败保持空，后续按非本人账号走 {puuid} 端点
        }
        return p;
    }

    /** 根据模式筛选重建卡片流。 */
    private void applyModeFilter() {
        String filter = modeFilter.getValue();
        cardList.getChildren().clear();
        for (GameRow row : allGames) {
            if (filter == null || "全部".equals(filter) || row.gameModeLabel().contains(filter)) {
                cardList.getChildren().add(buildGameCard(row));
            }
        }
    }

    /** 数据面板汇总：段位/场次/胜率/KD/常用英雄。 */
    private void updateSummonerPanel(List<GameRow> rows) {
        int total = rows.size();
        int wins = (int) rows.stream().filter(GameRow::win).count();
        int kills = rows.stream().mapToInt(GameRow::kills).sum();
        int deaths = rows.stream().mapToInt(GameRow::deaths).sum();
        int assists = rows.stream().mapToInt(GameRow::assists).sum();
        totalGamesLabel.setText(String.valueOf(total));
        winRateLabel.setText(total == 0 ? "0%" : String.format("%.0f%%", wins * 100.0 / total));
        kdLabel.setText(deaths == 0 ? String.format("%.1f", (double) kills) : String.format("%.1f", (kills + assists) / (double) deaths));

        // 常用英雄：按 championId 聚合场次与胜率，最多 5 个
        Map<Integer, int[]> heroAgg = new LinkedHashMap<>();
        for (GameRow row : rows) {
            int[] agg = heroAgg.computeIfAbsent(row.championId(), k -> new int[2]); // {场次, 胜场}
            agg[0]++;
            if (row.win()) {
                agg[1]++;
            }
        }
        heroStatsBox.getChildren().clear();
        heroAgg.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue()[0]))
                .limit(5)
                .forEach(e -> heroStatsBox.getChildren().add(heroStatRow(e.getKey(), e.getValue()[0], e.getValue()[1])));
        if (heroAgg.isEmpty()) {
            heroStatsBox.getChildren().add(new Label("暂无数据"));
        }
    }

    private Node heroStatRow(int championId, int games, int wins) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        ImageView icon = new ImageView();
        icon.setFitWidth(30);
        icon.setFitHeight(30);
        if (dd != null) {
            String url = dd.championIconUrl(championId);
            if (!url.isBlank()) {
                icon.setImage(new Image(url, true));
            }
        }
        Rectangle clip = new Rectangle(30, 30);
        clip.setArcWidth(8);
        clip.setArcHeight(8);
        icon.setClip(clip);
        Label name = new Label(championName(championId));
        name.getStyleClass().add("hero-stat-name");
        HBox.setHgrow(name, Priority.ALWAYS);
        Label stat = new Label(games + " 场 · " + (games == 0 ? "0%" : String.format("%.0f%%", wins * 100.0 / games)));
        stat.getStyleClass().add("hero-stat-val");
        row.getChildren().addAll(icon, name, stat);
        return row;
    }

    private GameRow toGameRow(JsonNode game, String myPuuid) {
        long gameId = game.path("gameId").asLong(0);
        String mode = modeLabel(game.path("gameMode").asText(""));
        long creation = game.path("gameCreation").asLong(0);
        // 1) 优先从 participantIdentities 中按 puuid 定位自己的 participantId
        int myParticipantId = -1;
        JsonNode identities = game.path("participantIdentities");
        JsonNode identityPlayer = null;
        if (identities.isArray()) {
            for (JsonNode identity : identities) {
                JsonNode player = identity.path("player");
                if (player != null && !player.isMissingNode()
                        && myPuuid.equals(player.path("puuid").asText(""))) {
                    myParticipantId = identity.path("participantId").asInt(-1);
                    identityPlayer = player;
                    break;
                }
            }
        }
        // 2) 在 participants 中按 participantId 匹配对应元素
        JsonNode me = null;
        if (myParticipantId > 0) {
            for (JsonNode p : game.path("participants")) {
                if (p.path("participantId").asInt(-1) == myParticipantId) {
                    me = p;
                    break;
                }
            }
        }
        // 3) 优雅降级：identities 缺失或 participantId 匹配不到时，直接按 puuid 匹配（兼容旧响应结构）
        if (me == null) {
            for (JsonNode p : game.path("participants")) {
                if (myPuuid.equals(p.path("puuid").asText(""))) {
                    me = p;
                    break;
                }
            }
        }
        if (me == null) {
            ctx.logger().warn("战绩解析跳过对局 gameId={}：participants 未匹配到玩家 puuid={}（identities participantId={}, 有player信息={}）",
                    gameId, myPuuid, myParticipantId, identityPlayer != null);
            return null;
        }
        int championId = me.path("championId").asInt(0);
        JsonNode stats = me.path("stats");
        boolean win = stats.path("win").asBoolean(false);
        int kills = stats.path("kills").asInt(0);
        int deaths = stats.path("deaths").asInt(0);
        int assists = stats.path("assists").asInt(0);
        // 扩展数据：补刀/金币/时长/地图/最大连杀/所属队伍（供新卡片展示与参与率计算）
        long damage = stats.path("totalDamageDealtToChampions").asLong(0);
        int cs = stats.path("totalMinionsKilled").asInt(0)
                + stats.path("neutralMinionsKilled").asInt(0);
        long goldEarned = stats.path("goldEarned").asLong(0);
        int gameDuration = game.path("gameDuration").asInt(0);
        int mapId = game.path("mapId").asInt(0);
        int largestMultiKill = stats.path("largestMultiKill").asInt(0);
        int teamId = me.path("teamId").asInt(0);
        // 击杀参与率：概要接口 participants 仅含当前玩家，无法求队伍总击杀，
        // 标记 -1 表示未知，展开队友/对手面板时按完整对局（/lol-match-history/v1/games/{id}）补全
        int killParticipation = -1;
        List<Integer> spells = List.of(
                me.path("spell1Id").asInt(0), me.path("spell2Id").asInt(0));
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            int itemId = stats.path("item" + i).asInt(0);
            if (itemId > 0 && items.size() < 6) {
                items.add(itemId);
            }
        }
        return new GameRow(gameId, mode, championName(championId), championId,
                win, kills, deaths, assists, creation, damage, cs, goldEarned,
                gameDuration, mapId, largestMultiKill, killParticipation, teamId,
                spells, items, game);
    }

    /**
     * 构建单张对局卡片（参考截图样式，从左到右）：
     * 英雄头像(圆形+边框+箭头) + 胜负文字(绿/红) + 召唤师技能图标(2格)；
     * 中部核心数据：英雄名 + KDA(击杀白/死亡红/助攻白) + KDA 比值·击杀参与率 +
     * 补刀/伤害/经济效率统计 + 6格装备 + 成就徽章(双杀等)；底部元信息行(模式·时长·时间·地图)；
     * 右侧可展开的队友/对手列表（折叠面板懒加载完整对局）。
     * 点击卡片仍可展开符文/出装/玩家详情，原交互不破坏。
     */
    private Node buildGameCard(GameRow row) {
        VBox card = new VBox(0);
        card.getStyleClass().addAll("game-card",
                row.win() ? "game-card-win" : "game-card-loss");

        HBox body = new HBox(14);
        body.setPadding(new Insets(12, 12, 0, 12));
        body.setAlignment(Pos.TOP_LEFT);

        // ---- 左列：英雄头像(圆形+箭头) + 胜负 + 召唤师技能 ----
        VBox leftCol = new VBox(6);
        leftCol.setAlignment(Pos.CENTER);

        StackPane avatarStack = new StackPane();
        ImageView champIcon = new ImageView();
        champIcon.setFitWidth(52);
        champIcon.setFitHeight(52);
        if (dd != null) {
            String url = dd.championIconUrl(row.championId());
            if (!url.isBlank()) {
                champIcon.setImage(new Image(url, true));
            }
        }
        Circle champClip = new Circle(26, 26, 26);
        champIcon.setClip(champClip);
        champIcon.getStyleClass().add("game-avatar");
        Label arrow = new Label("❯");
        arrow.getStyleClass().add("game-arrow");
        avatarStack.getChildren().addAll(champIcon, arrow);
        StackPane.setAlignment(arrow, Pos.BOTTOM_RIGHT);
        avatarStack.getStyleClass().addAll("game-avatar-ring",
                row.win() ? "avatar-ring-win" : "avatar-ring-loss");

        Label result = new Label(row.resultLabel());
        result.getStyleClass().addAll("result-text",
                row.win() ? "result-text-win" : "result-text-loss");

        VBox spellsBox = new VBox(4);
        spellsBox.setAlignment(Pos.CENTER);
        for (int spellId : row.spells()) {
            ImageView sp = new ImageView();
            sp.setFitWidth(26);
            sp.setFitHeight(26);
            Rectangle spClip = new Rectangle(26, 26);
            spClip.setArcWidth(6);
            spClip.setArcHeight(6);
            sp.setClip(spClip);
            if (dd != null && spellId > 0) {
                String url = dd.summonerSpellIconUrl(spellId);
                if (!url.isBlank()) {
                    sp.setImage(new Image(url, true));
                }
            }
            spellsBox.getChildren().add(sp);
        }

        leftCol.getChildren().addAll(avatarStack, result, spellsBox);

        // ---- 中列：核心数据 ----
        VBox midCol = new VBox(6);
        midCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(midCol, Priority.ALWAYS);

        // 行1：英雄名 + KDA 大字（击杀白 / 死亡红 / 助攻白）+ 小字（KDA 比值 · 参与率）
        HBox kdaRow = new HBox(12);
        kdaRow.setAlignment(Pos.CENTER_LEFT);
        Label heroName = new Label(row.championName());
        heroName.getStyleClass().add("card-hero-name");
        TextFlow kdaFlow = new TextFlow(
                kdaText(String.valueOf(row.kills()), "kda-kill"),
                kdaText(" / " + row.deaths() + " / ", "kda-death"),
                kdaText(String.valueOf(row.assists()), "kda-assist"));
        kdaFlow.getStyleClass().add("card-kda");
        Label kdaSub = new Label(row.kdaSubLabel());
        kdaSub.getStyleClass().add("kda-sub");
        kdaRow.getChildren().addAll(heroName, kdaFlow, kdaSub);
        HBox.setHgrow(kdaRow, Priority.ALWAYS);

        // 行2：补刀 / 伤害 / 经济效率
        HBox statsRow = new HBox(8);
        statsRow.setAlignment(Pos.CENTER_LEFT);
        statsRow.getChildren().addAll(
                statChip("补刀", row.csLabel()),
                statChip("伤害", row.damageLabel()),
                statChip("经济", row.econLabel()));

        // 行3：装备 6 格 + 成就徽章
        HBox itemsRow = new HBox(4);
        itemsRow.setAlignment(Pos.CENTER_LEFT);
        for (int itemId : row.items()) {
            ImageView iv = new ImageView();
            iv.setFitWidth(28);
            iv.setFitHeight(28);
            Rectangle iclip = new Rectangle(28, 28);
            iclip.setArcWidth(6);
            iclip.setArcHeight(6);
            iv.setClip(iclip);
            if (dd != null) {
                String url = dd.itemIconUrl(itemId);
                if (!url.isBlank()) {
                    iv.setImage(new Image(url, true));
                }
            }
            itemsRow.getChildren().add(iv);
        }
        for (int i = row.items().size(); i < 6; i++) {
            Label empty = new Label("·");
            empty.getStyleClass().add("item-slot-empty");
            itemsRow.getChildren().add(empty);
        }
        Label achievement = new Label(row.achievementLabel());
        achievement.getStyleClass().add(row.largestMultiKill() >= 2
                ? "achievement-badge" : "achievement-empty");
        itemsRow.getChildren().add(achievement);

        // 行4：底部元信息 + 详情提示
        HBox metaRow = new HBox(8);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        Label metaLine = new Label(row.metaLine());
        metaLine.getStyleClass().add("card-meta-line");
        Label rankBadge = new Label("查看详情 ▾");
        rankBadge.getStyleClass().add("card-detail-hint");
        HBox.setHgrow(metaLine, Priority.ALWAYS);
        metaRow.getChildren().addAll(metaLine, rankBadge);

        midCol.getChildren().addAll(kdaRow, statsRow, itemsRow, metaRow);

        // ---- 右列：队友/对手折叠面板 ----
        VBox rightCol = buildTeammatesPanel(row, kdaSub);

        body.getChildren().addAll(leftCol, midCol, rightCol);
        card.getChildren().add(body);

        // 详情展开区：点击卡片异步加载对局详情（保留原交互）
        VBox detailArea = new VBox(8);
        detailArea.setPadding(new Insets(8, 12, 12, 12));
        detailArea.setVisible(false);
        detailArea.setManaged(false);
        card.getChildren().add(detailArea);

        card.setOnMouseClicked(e -> {
            if (detailArea.isVisible()) {
                detailArea.setVisible(false);
                detailArea.setManaged(false);
                rankBadge.setText("查看详情 ▾");
                return;
            }
            rankBadge.setText("加载中...");
            loadAndRenderDetail(row, detailArea, rankBadge);
        });
        return card;
    }

    /** KDA 分段文本（击杀/死亡/助攻不同着色）。 */
    private Text kdaText(String value, String styleClass) {
        Text t = new Text(value);
        t.getStyleClass().add(styleClass);
        return t;
    }

    /** 统计小卡片：标签 + 主数值（补刀 / 伤害 / 经济效率）。 */
    private Node statChip(String labelText, String valueText) {
        VBox chip = new VBox(1);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("stat-chip");
        Label val = new Label(valueText);
        val.getStyleClass().add("stat-chip-value");
        Label cap = new Label(labelText);
        cap.getStyleClass().add("stat-chip-label");
        chip.getChildren().addAll(val, cap);
        return chip;
    }

    /**
     * 右侧队友/对手折叠面板：收起时一行按钮，展开时懒加载完整对局
     * （/lol-match-history/v1/games/{gameId} 返回双方 10 人）并渲染
     * 头像占位 + 昵称 + 英雄图标 + KDA，同时按完整数据补全击杀参与率。
     */
    private VBox buildTeammatesPanel(GameRow row, Label kdaSub) {
        VBox panel = new VBox(6);
        panel.setAlignment(Pos.TOP_LEFT);
        panel.getStyleClass().add("teammates-panel");

        Button toggle = new Button("队友 / 对手 ▾");
        toggle.getStyleClass().add("teammate-toggle");
        toggle.setOnAction(e -> {
            e.consume();
            expandTeammates(row, toggle, panel, kdaSub);
        });
        panel.getChildren().add(toggle);
        return panel;
    }

    private void expandTeammates(GameRow row, Button toggle, VBox panel, Label kdaSub) {
        List<Node> children = panel.getChildren();
        VBox existing = children.size() > 1 && children.get(1) instanceof VBox
                ? (VBox) children.get(1) : null;
        if (existing != null && existing.isVisible()) {
            existing.setVisible(false);
            existing.setManaged(false);
            toggle.setText("队友 / 对手 ▾");
            return;
        }
        final VBox listBox;
        if (existing == null) {
            listBox = new VBox(6);
            listBox.setPrefWidth(248);
            listBox.getStyleClass().add("teammates-box");
            panel.getChildren().add(listBox);
        } else {
            listBox = existing;
        }
        toggle.setText("加载中...");
        new Thread(() -> {
            try {
                LcuClient lcu = ctx.services().require(LcuClient.class);
                JsonNode detail = lcu.get("/lol-match-history/v1/games/" + row.gameId(), JsonNode.class);
                JsonNode finalDetail = detail;
                Platform.runLater(() -> {
                    if (finalDetail == null || finalDetail.isMissingNode()) {
                        listBox.getChildren().setAll(new Label("队友数据暂不可用"));
                    } else {
                        renderTeammates(listBox, row, finalDetail);
                        // 按完整对局补全击杀参与率并回写卡片小字
                        int kp = killParticipationOf(finalDetail, row.teamId(),
                                row.kills(), row.assists());
                        if (kp >= 0 && kdaSub != null) {
                            kdaSub.setText("KDA " + row.kdaRatioText() + " · 参与 " + kp + "%");
                        }
                    }
                    listBox.setVisible(true);
                    listBox.setManaged(true);
                    toggle.setText("收起队友/对手 ▴");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    listBox.getChildren().setAll(new Label("队友数据加载失败: " + ex.getMessage()));
                    listBox.setVisible(true);
                    listBox.setManaged(true);
                    toggle.setText("重试 ▾");
                });
            }
        }, "match-history-teammates").start();
    }

    /** 渲染队友/对手双列表：按队伍分组（100 蓝方 / 200 红方），每人头像占位+昵称+英雄图标+KDA。 */
    private void renderTeammates(VBox listBox, GameRow row, JsonNode detail) {
        Map<Integer, JsonNode> identityMap = new HashMap<>();
        for (JsonNode id : detail.path("participantIdentities")) {
            identityMap.put(id.path("participantId").asInt(-1), id.path("player"));
        }
        List<ParticipantRow> myTeam = new ArrayList<>();
        List<ParticipantRow> enemyTeam = new ArrayList<>();
        for (JsonNode p : detail.path("participants")) {
            int teamId = p.path("teamId").asInt(0);
            ParticipantRow pr = toParticipantRow(p, identityMap);
            if (teamId == row.teamId()) {
                myTeam.add(pr);
            } else {
                enemyTeam.add(pr);
            }
        }
        HBox rows = new HBox(10);
        rows.setAlignment(Pos.TOP_LEFT);
        rows.getChildren().add(teammateSection("我方", myTeam));
        rows.getChildren().add(teammateSection("敌方", enemyTeam));
        listBox.getChildren().setAll(rows);
    }

    /** 队伍分组列：头像占位 + 昵称 + 英雄图标 + KDA（胜负着色）。 */
    private Node teammateSection(String title, List<ParticipantRow> players) {
        VBox box = new VBox(4);
        box.getStyleClass().add("team-section");
        box.setPrefWidth(118);
        Label head = new Label(title);
        head.getStyleClass().add("team-head");
        box.getChildren().add(head);
        for (ParticipantRow p : players) {
            HBox line = new HBox(6);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("player-row");
            Label avatar = new Label(p.avatarLetter());
            avatar.getStyleClass().add("player-avatar");
            VBox info = new VBox(1);
            info.setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(p.summonerName());
            name.getStyleClass().add("player-name");
            name.setMaxWidth(70);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label kda = new Label(p.kdaLabel());
            kda.getStyleClass().add("player-kda");
            if (p.win()) {
                kda.getStyleClass().add("team-row-win");
            } else {
                kda.getStyleClass().add("team-row-loss");
            }
            info.getChildren().addAll(name, kda);
            line.getChildren().addAll(avatar, info);
            box.getChildren().add(line);
        }
        return box;
    }

    /** 击杀参与率：本人 (kills+assists) / 同队总击杀 * 100，未知返回 -1。 */
    private int killParticipationOf(JsonNode detail, int teamId, int myKills, int myAssists) {
        int teamKills = 0;
        for (JsonNode p : detail.path("participants")) {
            if (p.path("teamId").asInt(0) == teamId) {
                teamKills += p.path("stats").path("kills").asInt(0);
            }
        }
        if (teamKills <= 0) {
            return -1;
        }
        return (int) Math.round((myKills + myAssists) * 100.0 / teamKills);
    }

    private void loadAndRenderDetail(GameRow row, VBox detailArea, Label rankBadge) {
        new Thread(() -> {
            try {
                LcuClient lcu = ctx.services().require(LcuClient.class);
                // 单场完整对局：/lol-match-history/v1/games/{gameId}（products/matches/{id} 在国服返回 404）
                JsonNode detail = lcu.get("/lol-match-history/v1/games/" + row.gameId(), JsonNode.class);
                JsonNode finalDetail = detail;
                Platform.runLater(() -> {
                    detailArea.getChildren().clear();
                    renderDetail(detailArea, row, finalDetail);
                    detailArea.setVisible(true);
                    detailArea.setManaged(true);
                    rankBadge.setText("收起详情 ▴");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    detailArea.getChildren().clear();
                    detailArea.getChildren().add(new Label("详情加载失败: " + e.getMessage()));
                    detailArea.setVisible(true);
                    detailArea.setManaged(true);
                    rankBadge.setText("重试 ▾");
                });
            }
        }, "match-history-detail").start();
    }

    /** 渲染展开详情：符文图标序列 + 出装图标序列 + 双方玩家列表。 */
    private void renderDetail(VBox detailArea, GameRow row, JsonNode detail) {
        if (detail == null || detail.isMissingNode()) {
            detailArea.getChildren().add(new Label("无详情数据"));
            return;
        }

        // 当前玩家信息：完整对局 participants 不含 puuid，先经 participantIdentities 定位 participantId
        JsonNode me = null;
        int myParticipantId = -1;
        for (JsonNode id : detail.path("participantIdentities")) {
            JsonNode player = id.path("player");
            if (player != null && !player.isMissingNode()
                    && currentPuuid.equals(player.path("puuid").asText(""))) {
                myParticipantId = id.path("participantId").asInt(-1);
                break;
            }
        }
        if (myParticipantId > 0) {
            for (JsonNode p : detail.path("participants")) {
                if (p.path("participantId").asInt(-1) == myParticipantId) {
                    me = p;
                    break;
                }
            }
        }

        // 符文 + 出装图标序列
        HBox buildRow = new HBox(10);
        buildRow.setAlignment(Pos.CENTER_LEFT);
        if (me != null) {
            List<Node> runeNodes = runeIconNodes(me);
            List<Node> itemNodes = itemIconNodes(me);
            if (!runeNodes.isEmpty()) {
                Label runeLabel = new Label("符文");
                runeLabel.getStyleClass().add("detail-label");
                buildRow.getChildren().add(runeLabel);
                buildRow.getChildren().addAll(runeNodes);
            }
            if (!itemNodes.isEmpty()) {
                Label itemLabel = new Label("出装");
                itemLabel.getStyleClass().add("detail-label");
                buildRow.getChildren().add(itemLabel);
                buildRow.getChildren().addAll(itemNodes);
            }
            if (runeNodes.isEmpty() && itemNodes.isEmpty()) {
                buildRow.getChildren().add(new Label("无符文/出装数据"));
            }
        }
        detailArea.getChildren().add(buildRow);

        // 双方玩家列表（昵称需经 participantIdentities 关联）
        Map<Integer, JsonNode> identityMap = new HashMap<>();
        for (JsonNode id : detail.path("participantIdentities")) {
            identityMap.put(id.path("participantId").asInt(-1), id.path("player"));
        }
        HBox teamsRow = new HBox(14);
        teamsRow.setAlignment(Pos.TOP_LEFT);
        List<ParticipantRow> blue = new ArrayList<>();
        List<ParticipantRow> red = new ArrayList<>();
        for (JsonNode p : detail.path("participants")) {
            int teamId = p.path("teamId").asInt(0);
            ParticipantRow pr = toParticipantRow(p, identityMap);
            (teamId == 100 ? blue : red).add(pr);
        }
        teamsRow.getChildren().add(teamSection("蓝方", blue));
        teamsRow.getChildren().add(teamSection("红方", red));
        detailArea.getChildren().add(teamsRow);
    }

    /** 符文图标序列：主/副系页面图标 + 符文小图标（最多 3 个基石）。 */
    private List<Node> runeIconNodes(JsonNode participant) {
        List<Node> nodes = new ArrayList<>();
        JsonNode perks = participant.path("perks");
        if (perks.isMissingNode() || perks.isNull()) {
            return nodes;
        }
        int primaryStyle = perks.path("primaryStyleId").asInt(0);
        int subStyle = perks.path("subStyleId").asInt(0);
        if (dd != null) {
            if (primaryStyle > 0) {
                String url = dd.runePageIconUrl(String.valueOf(primaryStyle));
                if (!url.isBlank()) {
                    nodes.add(smallIcon(url));
                }
            }
            if (subStyle > 0) {
                String url = dd.runePageIconUrl(String.valueOf(subStyle));
                if (!url.isBlank()) {
                    nodes.add(smallIcon(url));
                }
            }
            int count = 0;
            for (JsonNode idNode : perks.path("perkIds")) {
                if (count >= 3) {
                    break;
                }
                int runeId = idNode.asInt(0);
                if (runeId > 0) {
                    String url = dd.runeIconUrl(String.valueOf(runeId));
                    if (!url.isBlank()) {
                        nodes.add(smallIcon(url));
                        count++;
                    }
                }
            }
        }
        return nodes;
    }

    /** 出装图标序列：stats.item0..item6。 */
    private List<Node> itemIconNodes(JsonNode participant) {
        List<Node> nodes = new ArrayList<>();
        if (dd == null) {
            return nodes;
        }
        JsonNode stats = participant.path("stats");
        for (int i = 0; i <= 6; i++) {
            int itemId = stats.path("item" + i).asInt(0);
            if (itemId > 0) {
                nodes.add(smallIcon(dd.itemIconUrl(itemId)));
            }
        }
        return nodes;
    }

    private ImageView smallIcon(String url) {
        ImageView iv = new ImageView();
        iv.setFitWidth(26);
        iv.setFitHeight(26);
        Rectangle clip = new Rectangle(26, 26);
        clip.setArcWidth(6);
        clip.setArcHeight(6);
        iv.setClip(clip);
        if (url != null && !url.isBlank()) {
            iv.setImage(new Image(url, true));
        }
        return iv;
    }

    private Node teamSection(String title, List<ParticipantRow> players) {
        VBox box = new VBox(4);
        box.getStyleClass().add("team-section");
        box.setPrefWidth(240);
        Label head = new Label(title);
        head.getStyleClass().add("team-head");
        box.getChildren().add(head);
        for (ParticipantRow p : players) {
            HBox line = new HBox(8);
            line.setAlignment(Pos.CENTER_LEFT);
            Label champ = new Label(p.championName());
            champ.getStyleClass().add("team-champ");
            Label name = new Label(p.summonerName());
            name.getStyleClass().add("team-name");
            HBox.setHgrow(name, Priority.ALWAYS);
            Label kda = new Label(p.kdaLabel());
            kda.getStyleClass().add("team-kda");
            Label result = new Label(p.win() ? "胜" : "负");
            result.getStyleClass().addAll("team-result", p.win() ? "result-win" : "result-loss");
            line.getChildren().addAll(champ, name, kda, result);
            box.getChildren().add(line);
        }
        return box;
    }

    private ParticipantRow toParticipantRow(JsonNode p, Map<Integer, JsonNode> identityMap) {
        String summoner = "未知玩家";
        JsonNode player = identityMap.get(p.path("participantId").asInt(-1));
        if (player != null && !player.isMissingNode()) {
            String gameName = player.path("gameName").asText("");
            String tagLine = player.path("tagLine").asText("");
            summoner = gameName + (tagLine.isBlank() ? "" : "#" + tagLine);
            if (summoner.isBlank() || summoner.equals("#")) {
                summoner = "未知玩家";
            }
        }
        int championId = p.path("championId").asInt(0);
        JsonNode stats = p.path("stats");
        boolean win = stats.path("win").asBoolean(false);
        int kills = stats.path("kills").asInt(0);
        int deaths = stats.path("deaths").asInt(0);
        int assists = stats.path("assists").asInt(0);
        return new ParticipantRow(championName(championId), summoner,
                kills + " / " + deaths + " / " + assists, win);
    }

    private String championName(int championId) {
        return championNames.computeIfAbsent(championId, id -> {
            String[] name = {String.valueOf(id)};
            if (dd != null) {
                dd.champion(id).ifPresent(c -> name[0] = c.name());
            }
            return name[0];
        });
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "CLASSIC" -> "召唤师峡谷";
            case "ARAM" -> "极地大乱斗";
            case "TFT" -> "云顶之弈";
            case "URF" -> "无限火力";
            case "ARENA" -> "斗魂竞技场";
            case "PRACTICETOOL" -> "训练模式";
            default -> mode;
        };
    }

    private void setStatus(String text, boolean error) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.getStyleClass().removeAll("status-ok", "status-error");
            statusLabel.getStyleClass().add(error ? "status-error" : "status-ok");
        });
    }

    /** 对局列表行数据。 */
    public record GameRow(long gameId, String gameMode, String championName, int championId,
                          boolean win, int kills, int deaths, int assists, long gameCreation,
                          long damage, int cs, long goldEarned, int gameDuration, int mapId,
                          int largestMultiKill, int killParticipation, int teamId,
                          List<Integer> spells, List<Integer> items, JsonNode gameNode) {

        public String resultLabel() {
            return win ? "胜利" : "失败";
        }

        public String gameModeLabel() {
            return gameMode;
        }

        public String kdaLabel() {
            return kills + " / " + deaths + " / " + assists;
        }

        /** 小字：KDA 比值 · 击杀参与率（未加载完整对局时为 --）。 */
        public String kdaSubLabel() {
            String ratio = "KDA " + kdaRatioText();
            if (killParticipation >= 0) {
                return ratio + " · 参与 " + killParticipation + "%";
            }
            return ratio + " · 参与 --";
        }

        /** KDA 比值（死亡为 0 时按击杀+助攻计）。 */
        public String kdaRatioText() {
            if (deaths == 0) {
                return String.format("%.2f", (double) (kills + assists));
            }
            return String.format("%.2f", (kills + assists) / (double) deaths);
        }

        /** 补刀数（小兵+野怪），0 视为缺失显示 --。 */
        public String csLabel() {
            return cs > 0 ? String.valueOf(cs) : "--";
        }

        /** 总伤害（千分位，如 23,049 伤害），0 表示数据缺失。 */
        public String damageLabel() {
            if (damage <= 0) {
                return "0 伤害";
            }
            return String.format("%,d 伤害", damage);
        }

        /** 经济效率：金币/分钟。 */
        public String econLabel() {
            if (goldEarned <= 0 || gameDuration <= 0) {
                return "-- /分";
            }
            double perMin = goldEarned * 60.0 / gameDuration;
            return String.format("%.1f /分", perMin);
        }

        /** 对局时长 mm:ss。 */
        public String durationLabel() {
            if (gameDuration <= 0) {
                return "--:--";
            }
            int m = gameDuration / 60;
            int s = gameDuration % 60;
            return String.format("%d:%02d", m, s);
        }

        /** 相对时间（x 分钟/小时/天前），超过 30 天显示日期。 */
        public String relativeTimeLabel() {
            long diff = System.currentTimeMillis() - gameCreation;
            long minutes = diff / 60000;
            if (minutes < 60) {
                return Math.max(0, minutes) + " 分钟前";
            }
            long hours = minutes / 60;
            if (hours < 24) {
                return hours + " 小时前";
            }
            long days = hours / 24;
            if (days < 30) {
                return days + " 天前";
            }
            return TIME_FMT.format(Instant.ofEpochMilli(gameCreation));
        }

        /** 地图名。 */
        public String mapLabel() {
            return switch (mapId) {
                case 11 -> "召唤师峡谷";
                case 12 -> "嚎哭深渊";
                case 21, 22 -> "屠夫之桥";
                case 30 -> "扭曲丛林";
                case 33 -> "试炼之地";
                default -> mapId > 0 ? "地图" + mapId : "--";
            };
        }

        /** 底部元信息：模式 · 时长 · 时间 · 地图。 */
        public String metaLine() {
            return gameMode + " · " + durationLabel() + " · " + relativeTimeLabel()
                    + " · " + mapLabel();
        }

        /** 成就徽章文字：双杀/三杀/四杀/五杀，否则占位横杠。 */
        public String achievementLabel() {
            return switch (largestMultiKill) {
                case 2 -> "双杀";
                case 3 -> "三杀";
                case 4 -> "四杀";
                case 5 -> "五杀";
                default -> "—";
            };
        }
    }

    /** 对局详情参与者行数据。 */
    public record ParticipantRow(String championName, String summonerName, String kdaLabel, boolean win) {

        /** 头像占位字符（昵称首字符大写，未知玩家用 ?）。 */
        public String avatarLetter() {
            if (summonerName == null || summonerName.isBlank()
                    || "未知玩家".equals(summonerName)) {
                return "?";
            }
            return summonerName.substring(0, 1).toUpperCase();
        }
    }
}
