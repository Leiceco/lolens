package com.lolens.modules.pickban;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 自动选人/禁用人面板：选人阶段展示预设配置与当前选/禁状态，叠加窗口右上角。
 */
public class PickBanPanel implements OverlayPanel {

    private final VBox root = new VBox(6);
    private final Label titleLabel = new Label("自动选人/禁用");
    private final Label statusLabel = new Label("等待选人...");
    private final Label presetLabel = new Label("");
    /** 预设文案（构造时固化），失败提示覆盖 presetLabel 后由 reset 恢复。 */
    private final String presetText;

    public PickBanPanel(List<Integer> pickIds, List<Integer> banIds) {
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        statusLabel.setStyle("-fx-text-fill: #d8d8e0; -fx-font-size: 14px; -fx-font-weight: bold;");
        presetLabel.setStyle(PanelStyles.MUTED);
        root.getChildren().addAll(titleLabel, statusLabel, presetLabel);

        StringBuilder preset = new StringBuilder();
        if (pickIds.isEmpty()) {
            preset.append("选人：未配置");
        } else {
            preset.append("选人：").append(pickIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }
        preset.append("  |  ");
        if (banIds.isEmpty()) {
            preset.append("禁用：未配置");
        } else {
            preset.append("禁用：").append(banIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }
        presetText = preset.toString();
        presetLabel.setText(presetText);
    }

    @Override
    public String id() {
        return "pick.ban";
    }

    @Override
    public String title() {
        return "自动选人/禁用";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.CHAMP_SELECT;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    public void showWaiting() {
        setStatus("等待我的选/禁回合...", "#d8d8e0");
    }

    public void showPicked(String championName) {
        setStatus("已自动选择: " + championName, "#4cd3a3");
    }

    public void showBanned(String championName) {
        setStatus("已自动禁用: " + championName, "#ffc400");
    }

    public void showAllHandled() {
        setStatus("选人进行中（本轮已处理）", "#d8d8e0");
    }

    public void showFailed(String action, String message) {
        setStatus("自动" + action + "失败，请手动操作", "#f83f6f");
        Platform.runLater(() -> presetLabel.setText(message == null ? "" : message));
    }

    public void reset() {
        Platform.runLater(() -> {
            statusLabel.setText("等待选人...");
            statusLabel.setStyle("-fx-text-fill: #d8d8e0; -fx-font-size: 14px; -fx-font-weight: bold;");
            presetLabel.setText(presetText);
        });
    }

    private void setStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        });
    }

    @Override
    public void onEvent(GameEvent event) {
        // 状态由模块轮询驱动
    }
}
