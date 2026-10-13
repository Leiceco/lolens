package com.lolens.modules.counter;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 对位推荐面板：列出敌方英雄及其推荐克制英雄，叠加窗口右下角。
 */
public class CounterPanel implements OverlayPanel {

    private final VBox root = new VBox(6);
    private final Label titleLabel = new Label("对位推荐");
    private final VBox contentBox = new VBox(4);

    public CounterPanel() {
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        root.getChildren().addAll(titleLabel, contentBox);
        Label empty = new Label("等待敌方选人...");
        empty.setStyle("-fx-text-fill: #a0a0b0; -fx-font-size: 11px;");
        contentBox.getChildren().add(empty);
    }

    @Override
    public String id() {
        return "counter.recommendation";
    }

    @Override
    public String title() {
        return "对位推荐";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.CHAMP_SELECT;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    /** 更新对位推荐列表。 */
    public void update(List<CounterRecommendationService.CounterSuggestion> suggestions) {
        Platform.runLater(() -> {
            contentBox.getChildren().clear();
            if (suggestions == null || suggestions.isEmpty()) {
                Label empty = new Label("暂无推荐");
                empty.setStyle("-fx-text-fill: #a0a0b0; -fx-font-size: 11px;");
                contentBox.getChildren().add(empty);
                return;
            }
            for (CounterRecommendationService.CounterSuggestion s : suggestions) {
                Label l = new Label(s.enemyChampion() + " → " + String.join(" / ", s.counters()));
                l.setStyle("-fx-text-fill: #d8d8e0; -fx-font-size: 11px;");
                contentBox.getChildren().add(l);
            }
        });
    }

    public void reset() {
        Platform.runLater(() -> {
            contentBox.getChildren().clear();
            Label empty = new Label("等待敌方选人...");
            empty.setStyle("-fx-text-fill: #a0a0b0; -fx-font-size: 11px;");
            contentBox.getChildren().add(empty);
        });
    }

    @Override
    public void onEvent(GameEvent event) {
        // 数据由模块直接驱动
    }
}
