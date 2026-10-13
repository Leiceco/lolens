package com.lolens.modules.enemy;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.services.opgg.model.SummonerProfile;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 敌方战绩面板：按玩家名展示段位 / 胜率 / 场次，叠加窗口右上角。
 */
public class EnemyStatsPanel implements OverlayPanel {

    private final VBox root = new VBox(6);
    private final Label titleLabel = new Label("敌方战绩速查");
    private final VBox contentBox = new VBox(4);
    private final Map<String, Label> playerRows = new LinkedHashMap<>();

    public EnemyStatsPanel() {
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        root.getChildren().addAll(titleLabel, contentBox);
    }

    @Override
    public String id() {
        return "enemy.stats";
    }

    @Override
    public String title() {
        return "敌方战绩速查";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.CHAMP_SELECT;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    /** 更新单个玩家的战绩行（异步回调线程）。 */
    public void updatePlayer(String summonerName, SummonerProfile profile, boolean offline) {
        Platform.runLater(() -> {
            Label row = playerRows.computeIfAbsent(summonerName, name -> {
                Label l = new Label();
                l.setStyle("-fx-text-fill: #d8d8e0; -fx-font-size: 11px;");
                contentBox.getChildren().add(l);
                return l;
            });
            if (profile == null) {
                row.setText(summonerName + "  — 查询失败" + (offline ? " (离线)" : ""));
            } else {
                row.setText(summonerName + "  " + profile.rankLabel()
                        + "  胜率 " + String.format("%.0f%%", profile.winRate() * 100)
                        + " (" + profile.totalGames() + "场)");
            }
        });
    }

    public void reset() {
        Platform.runLater(() -> {
            playerRows.clear();
            contentBox.getChildren().clear();
        });
    }

    @Override
    public void onEvent(GameEvent event) {
        // 数据由模块直接驱动
    }
}
