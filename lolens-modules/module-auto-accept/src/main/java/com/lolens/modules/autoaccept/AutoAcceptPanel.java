package com.lolens.modules.autoaccept;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 自动接受对局面板：匹配阶段展示 ready-check 状态与自动接受结果，叠加窗口居中。
 */
public class AutoAcceptPanel implements OverlayPanel {

    private final VBox root = new VBox(6);
    private final Label titleLabel = new Label("自动接受对局");
    private final Label statusLabel = new Label("等待匹配...");
    private final Label hintLabel = new Label("");

    public AutoAcceptPanel(boolean enabled) {
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        statusLabel.setStyle("-fx-text-fill: #d8d8e0; -fx-font-size: 14px; -fx-font-weight: bold;");
        hintLabel.setStyle(PanelStyles.MUTED);
        root.getChildren().addAll(titleLabel, statusLabel, hintLabel);
        if (enabled) {
            hintLabel.setText("开启中：检测到就绪将自动接受");
        } else {
            hintLabel.setText("已关闭：检测到就绪请手动确认");
            statusLabel.setText("自动接受已关闭");
        }
    }

    @Override
    public String id() {
        return "auto.accept";
    }

    @Override
    public String title() {
        return "自动接受对局";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.LOBBY || phase == GamePhase.MATCHMAKING;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    public void showSearching() {
        setStatus("匹配中...", "-fx-text-fill: #d8d8e0;");
    }

    public void showReadyCheck() {
        setStatus("检测到匹配就绪!", "-fx-text-fill: #ffc400;");
    }

    public void showAccepted() {
        setStatus("已自动接受，进入选人", "-fx-text-fill: #4cd3a3;");
    }

    public void showAcceptFailed(String message) {
        setStatus("自动接受失败，请手动确认", "-fx-text-fill: #f83f6f;");
        Platform.runLater(() -> hintLabel.setText(message == null ? "" : message));
    }

    public void showInProgress() {
        setStatus("已进入选人阶段", "-fx-text-fill: #4cd3a3;");
    }

    public void showIdle() {
        setStatus("等待匹配...", "-fx-text-fill: #d8d8e0;");
        hintLabel.setText("");
    }

    public void showDisabled() {
        setStatus("自动接受已关闭", "-fx-text-fill: #a0a0b0;");
        hintLabel.setText("可在设置中开启");
    }

    public void reset() {
        Platform.runLater(() -> {
            statusLabel.setText("等待匹配...");
            statusLabel.setStyle("-fx-text-fill: #d8d8e0; -fx-font-size: 14px; -fx-font-weight: bold;");
            hintLabel.setText("");
        });
    }

    private void setStatus(String text, String colorStyle) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle(colorStyle + " -fx-font-size: 14px; -fx-font-weight: bold;");
        });
    }

    @Override
    public void onEvent(GameEvent event) {
        // 状态由模块轮询驱动
    }
}
