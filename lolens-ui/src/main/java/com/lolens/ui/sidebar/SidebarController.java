package com.lolens.ui.sidebar;

import com.lolens.kernel.event.ConnectionState;
import com.lolens.ui.theme.Palette;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 主窗口左侧导航栏：Logo + 导航项 + 底部 LCU 连接状态。
 */
public class SidebarController {

    private final VBox root = new VBox();
    private final VBox navBox = new VBox(4);
    private final ScrollPane navScroll = new ScrollPane();
    private final Map<NavPage, VBox> navItems = new EnumMap<>(NavPage.class);
    /** 当前可见的导航项（模块注册了对应页面才显示，如战绩查询）。 */
    private final Set<NavPage> visiblePages = EnumSet.allOf(NavPage.class);
    private final Circle connectionDot = new Circle(4);
    private final Label connectionLabel = new Label("LCU 未连接");

    private Consumer<NavPage> onNavigate = page -> {
    };

    public SidebarController() {
        build();
    }

    private void build() {
        root.getStyleClass().add("app-sidebar");
        root.setPadding(new Insets(16, 10, 14, 10));

        root.getChildren().add(buildLogo());
        for (NavPage page : NavPage.values()) {
            navBox.getChildren().add(buildNavItem(page));
        }

        navScroll.setContent(navBox);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        navScroll.getStyleClass().add("sidebar-scroll");
        navScroll.setFocusTraversable(false);
        VBox.setVgrow(navScroll, Priority.ALWAYS);
        root.getChildren().add(navScroll);

        root.getChildren().add(buildConnection());
    }

    private HBox buildLogo() {
        HBox logo = new HBox(8);
        logo.setAlignment(Pos.CENTER_LEFT);
        logo.setPadding(new Insets(0, 6, 18, 6));
        Label mark = new Label("◆");
        mark.setStyle("-fx-text-fill: " + Palette.ACCENT + "; -fx-font-size: 20px;");
        Label text = new Label("LoLens");
        text.getStyleClass().add("sidebar-logo");
        logo.getChildren().addAll(mark, text);
        return logo;
    }

    private VBox buildNavItem(NavPage page) {
        VBox item = new VBox();
        item.getStyleClass().add("nav-item");
        item.setPadding(new Insets(8, 12, 8, 12));

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label(page.glyph());
        icon.setStyle("-fx-font-size: 14px;");
        Label text = new Label(page.label());
        row.getChildren().addAll(icon, text);

        item.getChildren().add(row);
        item.setOnMouseClicked(e -> select(page));
        navItems.put(page, item);
        item.setVisible(visiblePages.contains(page));
        return item;
    }

    /** 设置可见导航项集合（由主控制器根据已注册页面计算）。 */
    public void setVisiblePages(Set<NavPage> pages) {
        visiblePages.clear();
        if (pages != null) {
            visiblePages.addAll(pages);
        }
        navItems.forEach((page, item) -> item.setVisible(visiblePages.contains(page)));
    }

    private HBox buildConnection() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10, 10, 0, 10));
        connectionDot.setFill(Color.web(Palette.DANGER));
        connectionLabel.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 11px;");
        box.getChildren().addAll(connectionDot, connectionLabel);
        return box;
    }

    /** 切换选中导航项并触发回调。 */
    public void select(NavPage page) {
        navItems.forEach((p, item) -> {
            if (p == page) {
                if (!item.getStyleClass().contains("nav-item-active")) {
                    item.getStyleClass().add("nav-item-active");
                }
            } else {
                item.getStyleClass().remove("nav-item-active");
            }
        });
        onNavigate.accept(page);
    }

    public void setOnNavigate(Consumer<NavPage> onNavigate) {
        this.onNavigate = onNavigate == null ? page -> {
        } : onNavigate;
    }

    /** 更新底部连接状态（可在任意线程调用）。 */
    public void updateConnection(ConnectionState state) {
        Platform.runLater(() -> {
            connectionDot.setFill(Color.web(dotColor(state)));
            connectionLabel.setText(connectionText(state));
        });
    }

    public VBox node() {
        return root;
    }

    private String dotColor(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> Palette.SUCCESS;
            case CONNECTING -> Palette.WARNING;
            case ERROR, DISCONNECTED -> Palette.DANGER;
        };
    }

    private String connectionText(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> "LCU 已连接";
            case CONNECTING -> "LCU 连接中";
            case ERROR -> "LCU 异常";
            case DISCONNECTED -> "LCU 未连接";
        };
    }
}
