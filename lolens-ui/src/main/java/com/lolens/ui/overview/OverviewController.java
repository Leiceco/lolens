package com.lolens.ui.overview;

import com.lolens.kernel.Kernel;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.module.LolModule;
import com.lolens.ui.hotkey.HotkeyHudController;
import com.lolens.ui.theme.Palette;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * 概览页：游戏阶段 / 连接状态 / 功能模块 / 快捷键。
 */
public class OverviewController {

    private final Kernel kernel;
    private final HotkeyHudController hotkeyHud = new HotkeyHudController();
    private final VBox root = new VBox(24);

    private final Label phaseValue = new Label("未知");
    private final Label connectionValue = new Label("未连接");
    private final Circle connectionDot = new Circle(4);

    public OverviewController(Kernel kernel) {
        this.kernel = kernel;
        build();
    }

    private void build() {
        root.setPadding(new Insets(24));
        root.getChildren().addAll(statusCard(), modulesCard(), hotkeyCard());
    }

    public VBox node() {
        return root;
    }

    private VBox statusCard() {
        VBox card = card("当前状态");

        phaseValue.setStyle("-fx-text-fill: " + Palette.TEXT_PRIMARY + "; -fx-font-size: 13px;");
        card.getChildren().add(row("游戏阶段", phaseValue));

        connectionValue.setStyle("-fx-text-fill: " + Palette.TEXT_PRIMARY + "; -fx-font-size: 13px;");
        connectionDot.setFill(Color.web(Palette.DANGER));
        HBox conn = new HBox(8);
        conn.setAlignment(Pos.CENTER_LEFT);
        conn.getChildren().addAll(connectionDot, connectionValue);
        card.getChildren().add(row("LCU 连接", conn));
        return card;
    }

    private VBox modulesCard() {
        VBox card = card("功能模块");
        for (LolModule module : kernel.modules().all()) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(module.displayName());
            name.setStyle("-fx-text-fill: " + Palette.TEXT_PRIMARY + "; -fx-font-size: 13px;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label badge = new Label("已加载");
            badge.setStyle("-fx-text-fill: " + Palette.ACCENT + "; -fx-font-size: 11px;"
                    + " -fx-background-color: " + Palette.ACCENT_SOFT + ";"
                    + " -fx-background-radius: 4; -fx-padding: 2 8 2 8;");

            row.getChildren().addAll(name, spacer, badge);
            card.getChildren().add(row);
        }
        return card;
    }

    private VBox hotkeyCard() {
        VBox card = card("快捷键");
        card.getChildren().add(hotkeyHud.node());
        return card;
    }

    public void updatePhase(GamePhase phase) {
        Platform.runLater(() -> phaseValue.setText(phaseText(phase)));
    }

    public void updateConnection(ConnectionState state) {
        Platform.runLater(() -> {
            connectionValue.setText(connectionText(state));
            connectionDot.setFill(Color.web(dotColor(state)));
        });
    }

    private VBox card(String title) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label header = new Label(title);
        header.getStyleClass().add("card-title");
        card.getChildren().add(header);
        return card;
    }

    private HBox row(String label, Node value) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        Label key = new Label(label);
        key.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 13px; -fx-min-width: 72px;");
        row.getChildren().addAll(key, value);
        return row;
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

    private String connectionText(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> "已连接";
            case CONNECTING -> "连接中";
            case ERROR -> "异常";
            case DISCONNECTED -> "未连接";
        };
    }

    private String dotColor(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> Palette.SUCCESS;
            case CONNECTING -> Palette.WARNING;
            case ERROR, DISCONNECTED -> Palette.DANGER;
        };
    }
}
