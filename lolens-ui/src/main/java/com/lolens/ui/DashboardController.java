package com.lolens.ui;

import com.lolens.kernel.Kernel;
import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.ui.overlay.OverlayWindow;
import com.lolens.ui.hotkey.HotkeyHudController;
import com.lolens.ui.overview.OverviewController;
import com.lolens.ui.page.PageRegistry;
import com.lolens.ui.settings.SettingsController;
import com.lolens.ui.sidebar.NavPage;
import com.lolens.ui.sidebar.SidebarController;
import com.lolens.ui.theme.Palette;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * UI 主控制器：左侧导航栏（LeagueAkari 风格）+ 内容页切换 + 叠加窗口装配。
 */
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final Kernel kernel;
    private final OverlayWindow overlay;
    private final SettingsController settings;
    private final SidebarController sidebar = new SidebarController();
    private final OverviewController overview;
    private final StackPane content = new StackPane();
    private final VBox settingsPage;
    /** 全部可切换页面（概览/设置 + 模块注册的页面）。 */
    private final Map<NavPage, Node> pages = new EnumMap<>(NavPage.class);

    // 右侧信息栏（页面标题说明 / 运行状态 / 快捷键提示）
    private final VBox contextPanel = new VBox(16);
    private final Label contextTitle = new Label("概览");
    private final Label contextDesc = new Label();
    private final Label contextPhase = new Label("未知");
    private final Label contextConn = new Label("LCU 未连接");
    private final Circle contextConnDot = new Circle(4);

    private Stage primaryStage;

    public DashboardController(Kernel kernel, OverlayWindow overlay) {
        this.kernel = kernel;
        this.overlay = overlay;
        this.settings = new SettingsController(kernel.config());
        this.overview = new OverviewController(kernel);
        this.settingsPage = settings.build();
    }

    /** 初始化主界面与叠加窗口。 */
    public void init(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("LoLens 英雄联盟助手");
        stage.initStyle(StageStyle.DECORATED);
        stage.setMinWidth(920);
        stage.setMinHeight(600);

        BorderPane root = buildDashboard();
        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/css/dashboard.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();

        // 叠加窗口
        overlay.init(new Stage());

        // 状态联动
        kernel.onGamePhaseChanged(this::onPhaseChanged);
        kernel.onGameEvent(this::onEvent);
        kernel.onConnectionChanged(state -> {
            sidebar.updateConnection(state);
            overview.updateConnection(state);
            updateContextConnection(state);
        });

        // 设置保存后即时应用透明度
        settings.setOnSaved(() -> {
            AppSettings s = kernel.config().load("app", AppSettings.class, AppSettings.defaults());
            overlay.setBaseOpacity(s.overlayOpacity());
        });

        // 注册 Alt+H：任意游戏阶段下显隐叠加窗口
        kernel.hotkeys().register("Alt+H", EnumSet.allOf(GamePhase.class), overlay::toggle);

        // 默认选中概览页
        sidebar.select(NavPage.OVERVIEW);

        // 初始化状态
        Platform.runLater(() -> {
            onPhaseChanged(kernel.currentPhase());
            sidebar.updateConnection(kernel.connectionState());
            overview.updateConnection(kernel.connectionState());
        });
    }

    private BorderPane buildDashboard() {
        pages.put(NavPage.OVERVIEW, wrapPage(overview.node()));
        pages.put(NavPage.SETTINGS, wrapPage(settingsPage));
        kernel.services().find(PageRegistry.class).ifPresent(reg -> reg.pages().forEach(page -> {
            if (!pages.containsKey(page)) {
                Node node = reg.node(page);
                if (node != null) {
                    pages.put(page, wrapPage(node));
                }
            }
        }));
        content.getChildren().addAll(pages.values());

        // 仅显示有实际内容的导航项（未注册页面的模块禁用时不出现）
        Set<NavPage> visible = EnumSet.of(NavPage.OVERVIEW, NavPage.SETTINGS);
        visible.addAll(pages.keySet());
        sidebar.setVisiblePages(visible);
        sidebar.setOnNavigate(this::showPage);

        BorderPane root = new BorderPane();
        root.setLeft(sidebar.node());
        root.setCenter(content);
        root.setRight(buildContextPanel());
        return root;
    }

    /** 页面内容统一放入可滚动容器（已自带 ScrollPane 的页面直接复用，避免嵌套滚动）。 */
    private Node wrapPage(Node node) {
        if (node instanceof ScrollPane scroll) {
            // 复用分支同样收敛滚动策略：纵向按需显示、横向禁用、不抢焦点，保证各页面滚轮行为一致
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scroll.setFocusTraversable(false);
            scroll.getStyleClass().add("page-scroll");
            return scroll;
        }
        ScrollPane scroll = new ScrollPane(node);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("page-scroll");
        scroll.setFocusTraversable(false);
        return scroll;
    }

    /** 右侧信息栏：页面说明 / 运行状态 / 快捷键提示，与左侧导航呼应形成双栏层次。 */
    private VBox buildContextPanel() {
        contextPanel.setPadding(new Insets(16));
        contextPanel.getStyleClass().add("context-panel");
        contextPanel.setPrefWidth(210);
        contextPanel.setMinWidth(210);

        VBox infoCard = new VBox(8);
        infoCard.getStyleClass().add("context-card");
        contextTitle.getStyleClass().add("context-title");
        contextDesc.setWrapText(true);
        contextDesc.getStyleClass().add("context-desc");
        contextDesc.setText(pageDesc(NavPage.OVERVIEW));
        infoCard.getChildren().addAll(contextTitle, contextDesc);

        VBox statusCard = new VBox(10);
        statusCard.getStyleClass().add("context-card");
        Label statusHead = new Label("运行状态");
        statusHead.getStyleClass().add("context-head");
        contextConnDot.setFill(Color.web(Palette.DANGER));
        statusCard.getChildren().addAll(
                statusHead,
                contextRow("游戏阶段", contextPhase),
                contextRow("LCU 连接", connValue()));

        VBox hotkeyCard = new VBox(10);
        hotkeyCard.getStyleClass().add("context-card");
        Label hotkeyHead = new Label("快捷键");
        hotkeyHead.getStyleClass().add("context-head");
        hotkeyCard.getChildren().addAll(
                hotkeyHead,
                contextHint("Alt+H", "显隐叠加窗口"),
                contextHint("Alt+1~6", "记录技能使用"),
                contextHint("Alt+Q", "发送技能 CD"));

        contextPanel.getChildren().addAll(infoCard, statusCard, hotkeyCard);
        return contextPanel;
    }

    private HBox connValue() {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        contextConn.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 12px;");
        box.getChildren().addAll(contextConnDot, contextConn);
        return box;
    }

    private HBox contextRow(String name, Node value) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label key = new Label(name);
        key.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 12px; -fx-min-width: 62px;");
        row.getChildren().addAll(key, value);
        return row;
    }

    private HBox contextHint(String key, String desc) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label k = new Label(key);
        k.getStyleClass().add("context-hint-key");
        Label d = new Label(desc);
        d.setWrapText(true);
        d.getStyleClass().add("context-hint-desc");
        row.getChildren().addAll(k, d);
        return row;
    }

    private String pageDesc(NavPage page) {
        return switch (page) {
            case OVERVIEW -> "游戏阶段、LCU 连接、功能模块与快捷键总览";
            case BUILD -> "选人阶段自动拉取 OP.GG 推荐出装、符文与技能加点";
            case MATCH_HISTORY -> "查询近期对局战绩与对局详情";
            case SETTINGS -> "模块开关、叠加透明度与发送冷却等运行参数";
        };
    }

    private String phaseText(GamePhase phase) {
        return switch (phase) {
            case UNKNOWN -> "未知";
            case LOBBY -> "大厅";
            case MATCHMAKING -> "匹配中";
            case CHAMP_SELECT -> "选人阶段";
            case LOADING -> "载入中";
            case IN_PROGRESS -> "对局进行中";
            case END_OF_GAME -> "结算";
            case NONE -> "离线";
        };
    }

    private void updateContext(NavPage page) {
        contextTitle.setText(page.label());
        contextDesc.setText(pageDesc(page));
    }

    private void showPage(NavPage page) {
        pages.forEach((p, node) -> {
            boolean visible = p == page;
            node.setVisible(visible);
            node.setManaged(visible);
        });
        updateContext(page);
    }

    /** 游戏阶段联动：更新叠加窗口与概览页。 */
    private void onPhaseChanged(GamePhase phase) {
        overlay.onGamePhaseChanged(phase);
        overview.updatePhase(phase);
        String text = phaseText(phase);
        Platform.runLater(() -> contextPhase.setText(text));
        log.debug("游戏阶段变更: {}", phase);
    }

    /** 右侧信息栏 LCU 连接状态同步。 */
    private void updateContextConnection(ConnectionState state) {
        Platform.runLater(() -> {
            contextConn.setText(connectionText(state));
            contextConnDot.setFill(Color.web(dotColor(state)));
        });
    }

    private String connectionText(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> "LCU 已连接";
            case CONNECTING -> "LCU 连接中";
            case ERROR -> "LCU 异常";
            case DISCONNECTED -> "LCU 未连接";
        };
    }

    private String dotColor(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> Palette.SUCCESS;
            case CONNECTING -> Palette.WARNING;
            case ERROR, DISCONNECTED -> Palette.DANGER;
        };
    }

    private void onEvent(GameEvent event) {
        overlay.dispatch(event);
    }

    /** 保存设置回调。 */
    public void saveSettings() {
        settings.save();
    }

    /** 关闭时释放资源。 */
    public void shutdown() {
        kernel.shutdown();
        overlay.shutdown();
    }
}
