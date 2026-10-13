package com.lolens.modules.skill;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.model.LiveGameData;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 技能 CD 面板：对局中每秒刷新我方召唤师技能剩余冷却，并支持发送摘要。
 */
public class SkillTimerPanel implements OverlayPanel {

    private final SkillTracker tracker;
    private final VBox root = new VBox(4);
    private final Label titleLabel = new Label("技能CD计时");
    private final Label meLabel = new Label("未在对局中");
    private final Label spell1 = new Label("闪现: 就绪");
    private final Label spell2 = new Label("传送: 就绪");
    private final Label qwer = new Label("QWER: 未记录");
    private final Label status = new Label("");

    private volatile String spell1Name = "SummonerFlash";
    private volatile String spell2Name = "SummonerTeleport";

    public SkillTimerPanel(SkillTracker tracker) {
        this.tracker = tracker;
        root.setStyle(PanelStyles.PANEL_BACKGROUND);
        titleLabel.setStyle(PanelStyles.TITLE);
        meLabel.setStyle("-fx-text-fill: #e8e8ea; -fx-font-size: 12px; -fx-font-weight: bold;");
        spell1.setStyle("-fx-text-fill: #8ab4ff; -fx-font-size: 12px;");
        spell2.setStyle("-fx-text-fill: #8ab4ff; -fx-font-size: 12px;");
        qwer.setStyle("-fx-text-fill: #9fe8b0; -fx-font-size: 11px;");
        status.setStyle("-fx-text-fill: #4cd3a3; -fx-font-size: 10px;");
        root.getChildren().addAll(titleLabel, meLabel, spell1, spell2, qwer, status);
    }

    @Override
    public String id() {
        return "skill.timer";
    }

    @Override
    public String title() {
        return "技能CD计时";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.IN_PROGRESS || phase == GamePhase.LOADING;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    /** 对局数据到达时更新英雄名与技能名（异步线程）。 */
    public void update(LiveGameData data, LiveGameData.LivePlayer me) {
        Platform.runLater(() -> {
            if (me == null) {
                meLabel.setText("等待我方玩家数据...");
                return;
            }
            meLabel.setText(me.championName() + " (Lv." + me.level() + ")");
            if (me.summonerSpells() != null) {
                spell1Name = me.summonerSpells().summonerSpellOne();
                spell2Name = me.summonerSpells().summonerSpellTwo();
            }
            refresh();
        });
    }

    /** 每秒刷新剩余 CD（调度线程 → FX 线程）。 */
    public void refresh() {
        Platform.runLater(() -> {
            spell1.setText(spellLabel(spell1Name, "SPELL1"));
            spell2.setText(spellLabel(spell2Name, "SPELL2"));
            qwer.setText(qwerLabel());
        });
    }

    /** QWER 记录状态：Alt+1~4 已标记的最近施放。 */
    private String qwerLabel() {
        StringBuilder sb = new StringBuilder("QWER: ");
        boolean any = false;
        for (String s : new String[]{"Q", "W", "E", "R"}) {
            if (tracker.wasCast(s)) {
                sb.append(s).append("✓ ");
                any = true;
            } else {
                sb.append(s).append("– ");
            }
        }
        if (!any) {
            return "QWER: 未记录（Alt+1~4 标记 QWER，Alt+5/6 标记召唤师技能）";
        }
        return sb.toString().trim();
    }

    private String spellLabel(String spellKey, String slot) {
        String cd = tracker.wasCast(slot)
                ? String.format("%d秒", tracker.remainingSeconds(slot, tracker.cooldownOf(spellKey)))
                : "就绪";
        return spellDisplayName(spellKey) + ": " + cd;
    }

    /** 生成可发送的 CD 摘要。 */
    public String buildSummary() {
        return "我方技能CD " + spellDisplayName(spell1Name) + " "
                + remainingText("SPELL1", spell1Name) + " | " + spellDisplayName(spell2Name) + " "
                + remainingText("SPELL2", spell2Name);
    }

    private String remainingText(String slot, String spellKey) {
        if (!tracker.wasCast(slot)) {
            return "可用";
        }
        return tracker.remainingSeconds(slot, tracker.cooldownOf(spellKey)) + "秒";
    }

    public void showSent(String summary) {
        Platform.runLater(() -> status.setText("已发送: " + summary));
    }

    public void setInGame(boolean inGame) {
        Platform.runLater(() -> {
            if (!inGame) {
                meLabel.setText("未在对局中");
                spell1.setText(spellDisplayName(spell1Name) + ": 就绪");
                spell2.setText(spellDisplayName(spell2Name) + ": 就绪");
                qwer.setText("QWER: 未记录");
                status.setText("");
            }
        });
    }

    private static String spellDisplayName(String key) {
        return switch (key) {
            case "SummonerFlash" -> "闪现";
            case "SummonerTeleport" -> "传送";
            case "SummonerDot" -> "点燃";
            case "SummonerHeal" -> "治疗";
            case "SummonerBarrier" -> "屏障";
            case "SummonerExhaust" -> "虚弱";
            case "SummonerHaste" -> "疾跑";
            case "SummonerBoost" -> "净化";
            case "SummonerSmite" -> "惩戒";
            default -> key.replace("Summoner", "");
        };
    }

    @Override
    public void onEvent(GameEvent event) {
        // 数据由模块直接驱动
    }
}
