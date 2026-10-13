package com.lolens.ui.sidebar;

/** 主窗口侧边栏导航页。 */
public enum NavPage {

    OVERVIEW("概览", "◈"),
    BUILD("出装/天赋", "◆"),
    MATCH_HISTORY("战绩查询", "☰"),
    SETTINGS("设置", "⚙");

    private final String label;
    private final String glyph;

    NavPage(String label, String glyph) {
        this.label = label;
        this.glyph = glyph;
    }

    public String label() {
        return label;
    }

    public String glyph() {
        return glyph;
    }
}
