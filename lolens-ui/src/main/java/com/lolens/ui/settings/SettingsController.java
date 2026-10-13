package com.lolens.ui.settings;

import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.config.ConfigManager;
import com.lolens.ui.theme.Palette;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置界面：模块开关、叠加窗口透明度、发送冷却。
 * 配置读写由内核 ConfigManager 完成；模块开关下次启动生效，透明度/冷却保存后即时生效。
 */
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final ConfigManager config;

    private final CheckBox buildEnabled = new CheckBox();
    private final CheckBox counterEnabled = new CheckBox();
    private final CheckBox enemyStatsEnabled = new CheckBox();
    private final CheckBox skillTimerEnabled = new CheckBox();
    private final CheckBox autoAcceptEnabled = new CheckBox();
    private final CheckBox respawnTimerEnabled = new CheckBox();
    private final CheckBox matchHistoryEnabled = new CheckBox();
    private final CheckBox pickBanEnabled = new CheckBox();
    private final CheckBox honorEnabled = new CheckBox();
    private final CheckBox autoReplyEnabled = new CheckBox();
    private final CheckBox lobbyFlowEnabled = new CheckBox();
    private final CheckBox chatStatusEnabled = new CheckBox();
    private final TextField overlayOpacity = new TextField("0.92");
    private final TextField sendCooldown = new TextField("5");
    private final TextField autoAcceptDelay = new TextField("0");
    private final Label saveStatus = new Label("");

    private Runnable onSaved = () -> {
    };

    public SettingsController(ConfigManager config) {
        this.config = config;
    }

    /** 构建设置页，并用已保存配置初始化控件。 */
    public VBox build() {
        AppSettings s = config.load("app", AppSettings.class, AppSettings.defaults());
        buildEnabled.setSelected(s.buildEnabled());
        counterEnabled.setSelected(s.counterEnabled());
        enemyStatsEnabled.setSelected(s.enemyStatsEnabled());
        skillTimerEnabled.setSelected(s.skillTimerEnabled());
        autoAcceptEnabled.setSelected(s.autoAcceptEnabled());
        respawnTimerEnabled.setSelected(s.respawnTimerEnabled());
        matchHistoryEnabled.setSelected(s.matchHistoryEnabled());
        pickBanEnabled.setSelected(s.pickBanEnabled());
        honorEnabled.setSelected(s.honorEnabled());
        autoReplyEnabled.setSelected(s.autoReplyEnabled());
        lobbyFlowEnabled.setSelected(s.lobbyFlowEnabled());
        chatStatusEnabled.setSelected(s.chatStatusEnabled());
        overlayOpacity.setText(String.valueOf(s.overlayOpacity()));
        sendCooldown.setText(String.valueOf(s.sendCooldownSeconds()));
        autoAcceptDelay.setText(String.valueOf(s.autoAcceptDelaySeconds()));

        VBox page = new VBox(24);
        page.setPadding(new Insets(24));

        Label header = new Label("设置");
        header.setStyle("-fx-text-fill: " + Palette.TEXT_PRIMARY
                + "; -fx-font-size: 20px; -fx-font-weight: bold;");
        page.getChildren().add(header);

        VBox moduleCard = card("功能模块");
        moduleCard.getChildren().addAll(
                toggleRow("启用出装推荐", buildEnabled),
                toggleRow("启用对位推荐", counterEnabled),
                toggleRow("启用敌方战绩", enemyStatsEnabled),
                toggleRow("启用技能 CD 计时", skillTimerEnabled),
                toggleRow("启用自动接受对局", autoAcceptEnabled),
                toggleRow("启用重生倒计时", respawnTimerEnabled),
                toggleRow("启用战绩查询", matchHistoryEnabled),
                toggleRow("启用自动选人/禁用", pickBanEnabled),
                toggleRow("启用自动点赞队友", honorEnabled),
                toggleRow("启用自动回复", autoReplyEnabled),
                toggleRow("启用返回房间/重赛", lobbyFlowEnabled),
                toggleRow("启用在线状态自定义", chatStatusEnabled));
        page.getChildren().add(moduleCard);

        VBox overlayCard = card("叠加窗口");
        overlayCard.getChildren().add(fieldRow("透明度 (0.1~1.0)", overlayOpacity));
        overlayCard.getChildren().add(fieldRow("发送冷却(秒)", sendCooldown));
        overlayCard.getChildren().add(fieldRow("自动接受延迟(秒，重启生效)", autoAcceptDelay));
        page.getChildren().add(overlayCard);

        page.getChildren().add(hotkeyCard());

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_LEFT);
        Button saveButton = new Button("保存设置");
        saveButton.setOnAction(e -> save());
        saveStatus.setStyle("-fx-text-fill: " + Palette.SUCCESS + "; -fx-font-size: 12px;");
        actions.getChildren().addAll(saveButton, saveStatus);
        page.getChildren().add(actions);

        return page;
    }

    /** 保存当前设置到 ConfigManager。 */
    public void save() {
        AppSettings s = new AppSettings(
                buildEnabled.isSelected(),
                counterEnabled.isSelected(),
                enemyStatsEnabled.isSelected(),
                skillTimerEnabled.isSelected(),
                autoAcceptEnabled.isSelected(),
                respawnTimerEnabled.isSelected(),
                matchHistoryEnabled.isSelected(),
                pickBanEnabled.isSelected(),
                honorEnabled.isSelected(),
                autoReplyEnabled.isSelected(),
                lobbyFlowEnabled.isSelected(),
                chatStatusEnabled.isSelected(),
                parseAutoAcceptDelay(),
                parseOpacity(),
                parseCooldown());
        config.save("app", s);
        saveStatus.setText("已保存（模块开关重启后生效）");
        log.info("保存设置: {}", s);
        onSaved.run();
    }

    /** 保存成功后的回调，用于即时应用透明度等运行期配置。 */
    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved == null ? () -> {
        } : onSaved;
    }

    private VBox card(String title) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label header = new Label(title);
        header.getStyleClass().add("card-title");
        card.getChildren().add(header);
        return card;
    }

    /** 快捷键说明卡片（与 README 热键约定一致）。 */
    private VBox hotkeyCard() {
        VBox card = card("快捷键说明");
        card.getChildren().addAll(
                hotkeyRow("Alt+1", "记录 Q 技能使用"),
                hotkeyRow("Alt+2", "记录 W 技能使用"),
                hotkeyRow("Alt+3", "记录 E 技能使用"),
                hotkeyRow("Alt+4", "记录 R 技能使用"),
                hotkeyRow("Alt+5", "记录召唤师技能 1 使用"),
                hotkeyRow("Alt+6", "记录召唤师技能 2 使用"),
                hotkeyRow("Alt+H", "显隐叠加窗口"),
                hotkeyRow("Alt+Q", "发送技能 CD 信息到游戏聊天"));
        Label tip = new Label("热键仅在对局中生效（Alt+H 全阶段可用），由 JNativeHook 全局监听。");
        tip.setStyle("-fx-text-fill: " + Palette.TEXT_MUTED + "; -fx-font-size: 11px;");
        card.getChildren().add(tip);
        return card;
    }

    /** 功能开关行（对齐 SettingsRow：label 左、开关右、底部细分隔线）。 */
    private HBox toggleRow(String name, CheckBox box) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        Label key = new Label(name);
        key.setStyle("-fx-text-fill: " + Palette.TEXT_PRIMARY + "; -fx-font-size: 13px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(key, spacer, box);
        return row;
    }

    private HBox hotkeyRow(String key, String desc) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        Label k = new Label(key);
        k.setStyle("-fx-text-fill: " + Palette.ACCENT
                + "; -fx-font-weight: bold; -fx-font-size: 12px; -fx-min-width: 80px;");
        Label d = new Label(desc);
        d.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 12px;");
        row.getChildren().addAll(k, d);
        return row;
    }

    private HBox fieldRow(String label, TextField field) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        Label key = new Label(label);
        key.setStyle("-fx-text-fill: " + Palette.TEXT_PRIMARY + "; -fx-font-size: 13px;");
        field.setPrefWidth(120);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(key, spacer, field);
        return row;
    }

    private double parseOpacity() {
        try {
            return Math.max(0.1, Math.min(1.0, Double.parseDouble(overlayOpacity.getText())));
        } catch (NumberFormatException e) {
            return 0.92;
        }
    }

    private int parseCooldown() {
        try {
            return Math.max(1, Integer.parseInt(sendCooldown.getText()));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private int parseAutoAcceptDelay() {
        try {
            return Math.max(0, Integer.parseInt(autoAcceptDelay.getText()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
