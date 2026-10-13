package com.lolens.modules.core.overlay;

import com.lolens.kernel.event.ConnectionStateChanged;
import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 核心状态面板：展示当前游戏阶段与 LCU 连接状态，常驻叠加窗口左上角。
 */
public class CoreStatusPanel implements OverlayPanel {

    private final VBox root = new VBox(4);
    private final Label titleLabel = new Label("LoLens");
    private final Label phaseLabel = new Label("阶段: 未连接");
    private final Label connLabel = new Label("LCU: 未连接");

    public CoreStatusPanel() {
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        phaseLabel.setStyle("-fx-text-fill: #e8e8ea; -fx-font-size: 11px;");
        connLabel.setStyle("-fx-text-fill: #4cd3a3; -fx-font-size: 11px;");
        root.getChildren().addAll(titleLabel, phaseLabel, connLabel);
    }

    @Override
    public String id() {
        return "core.status";
    }

    @Override
    public String title() {
        return "核心状态";
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase != GamePhase.NONE;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    public void onPhaseChanged(GamePhaseChanged event) {
        Platform.runLater(() -> phaseLabel.setText("阶段: " + phaseText(event.to())));
    }

    public void onConnectionChanged(ConnectionStateChanged event) {
        Platform.runLater(() -> connLabel.setText("LCU: " + event.to()));
    }

    @Override
    public void onEvent(GameEvent event) {
        // 核心事件已在订阅回调中处理
    }

    private static String phaseText(GamePhase phase) {
        return switch (phase) {
            case UNKNOWN -> "未连接";
            case LOBBY -> "大厅";
            case MATCHMAKING -> "匹配中";
            case CHAMP_SELECT -> "选人阶段";
            case LOADING -> "载入中";
            case IN_PROGRESS -> "对局进行中";
            case END_OF_GAME -> "结算";
            case NONE -> "离线";
        };
    }
}
