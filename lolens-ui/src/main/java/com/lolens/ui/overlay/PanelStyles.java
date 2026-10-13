package com.lolens.ui.overlay;

/**
 * 叠加面板共享样式（LeagueAkari 风格深色卡片）。
 */
public final class PanelStyles {

    private PanelStyles() {
    }

    /** 深色半透明卡片背景（对齐 LeagueAkari dark 卡片，提高不透明度与边框可见度）。 */
    public static final String PANEL_BACKGROUND = "-fx-background-color: rgba(16,17,20,0.82);"
            + "-fx-background-radius: 10;"
            + "-fx-padding: 12 16 12 16;"
            + "-fx-border-color: rgba(255,255,255,0.12);"
            + "-fx-border-radius: 10;";

    /** 面板标题。 */
    public static final String TITLE = "-fx-text-fill: rgba(255,255,255,0.90); -fx-font-weight: bold; -fx-font-size: 13px;";

    /** 正文浅色小字。 */
    public static final String BODY = "-fx-text-fill: rgba(255,255,255,0.72); -fx-font-size: 11px;";

    /** 强调（粉红加粗标题点缀）。 */
    public static final String ACCENT = "-fx-text-fill: #f83f6f; -fx-font-weight: bold; -fx-font-size: 13px;";

    /** 弱化文字。 */
    public static final String MUTED = "-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 11px;";
}
