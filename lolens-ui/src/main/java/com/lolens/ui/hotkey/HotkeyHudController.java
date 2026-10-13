package com.lolens.ui.hotkey;

import com.lolens.ui.theme.Palette;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 热键提示：列出当前可用热键。无背景容器，由外层卡片承载。
 */
public class HotkeyHudController {

    private final VBox root = new VBox(6);

    public HotkeyHudController() {
        root.getChildren().addAll(
                hint("Alt+1 ~ Alt+4", "记录 Q / W / E / R 技能使用"),
                hint("Alt+5 / Alt+6", "记录两个召唤师技能使用"),
                hint("Alt+H", "显隐叠加窗口"),
                hint("Alt+Q", "发送技能 CD 摘要到游戏聊天"));
    }

    private HBox hint(String key, String desc) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        Label k = new Label(key);
        k.setStyle("-fx-text-fill: " + Palette.ACCENT
                + "; -fx-font-weight: bold; -fx-font-size: 12px; -fx-min-width: 110px;");
        Label d = new Label(desc);
        d.setStyle("-fx-text-fill: " + Palette.TEXT_SECONDARY + "; -fx-font-size: 12px;");
        row.getChildren().addAll(k, d);
        return row;
    }

    public VBox node() {
        return root;
    }
}
