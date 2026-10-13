package com.lolens.modules.respawn;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 重生倒计时面板：对局中死亡时在叠加窗口中央显示复活倒计时大数字。
 */
public class RespawnTimerPanel implements OverlayPanel {

    private final VBox root = new VBox(6);
    private final Label titleLabel = new Label("重生倒计时");
    private final Label countdownLabel = new Label("");
    private final Label heroLabel = new Label("");

    public RespawnTimerPanel() {
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        countdownLabel.setStyle("-fx-text-fill: #f83f6f; -fx-font-size: 48px; -fx-font-weight: bold;");
        heroLabel.setStyle(PanelStyles.MUTED);
        root.getChildren().addAll(titleLabel, countdownLabel, heroLabel);
        root.setVisible(false);
        root.setManaged(false);
    }

    @Override
    public String id() {
        return "respawn.timer";
    }

    @Override
    public String title() {
        return "重生倒计时";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.IN_PROGRESS;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    /** 死亡时显示初始倒计时（调度线程）。 */
    public void showDeath(long totalSeconds) {
        Platform.runLater(() -> {
            root.setVisible(true);
            root.setManaged(true);
            countdownLabel.setText(totalSeconds + "s");
        });
    }

    /** 每秒刷新剩余秒数（调度线程）。 */
    public void updateCountdown(long remaining) {
        Platform.runLater(() -> {
            if (root.isVisible()) {
                countdownLabel.setText(remaining + "s");
            }
        });
    }

    /** 复活/离开对局时隐藏倒计时。 */
    public void hideDeath() {
        Platform.runLater(() -> {
            root.setVisible(false);
            root.setManaged(false);
            countdownLabel.setText("");
        });
    }

    /** 更新英雄名（GameDataUpdated 到达时）。 */
    public void updateHero(String championName) {
        Platform.runLater(() -> heroLabel.setText(championName == null ? "" : championName));
    }

    @Override
    public void onEvent(GameEvent event) {
        // 状态由模块轮询驱动
    }
}
