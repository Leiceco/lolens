package com.lolens.ui.theme;

/**
 * LoLens 全局配色（LeagueAkari 风格）。
 * Java 代码里需要动态上色（状态点、选中态、发光等）的地方统一从这里取值，
 * 与 {@code css/dashboard.css} 里的结构样式共用同一套色值。
 */
public final class Palette {

    private Palette() {
    }

    /** 窗口 / 内容区背景（对齐 LeagueAkari dark --la-color-bg-primary）。 */
    public static final String WINDOW_BG = "#141416";
    /** 侧边栏背景（半透明深色，对齐 --la-sidebar-bg）。 */
    public static final String SIDEBAR_BG = "rgba(0,0,0,0.28)";
    /** 卡片背景（半透明表面，对齐 --la-card-surface-90）。 */
    public static final String CARD_BG = "rgba(20,20,22,0.92)";
    /** 卡片 / 导航悬停背景。 */
    public static final String CARD_HOVER = "rgba(255,255,255,0.07)";

    /** 细分隔线 / 控件边框。 */
    public static final String BORDER = "rgba(255,255,255,0.10)";

    /** 主文字（对齐 --la-color-text-primary）。 */
    public static final String TEXT_PRIMARY = "rgba(255,255,255,0.86)";
    /** 次文字。 */
    public static final String TEXT_SECONDARY = "rgba(255,255,255,0.62)";
    /** 弱文字 / 占位。 */
    public static final String TEXT_MUTED = "rgba(255,255,255,0.45)";

    /** 强调粉红（LeagueAkari sakura 系招牌色）。 */
    public static final String ACCENT = "#f83f6f";
    /** 强调色软背景。 */
    public static final String ACCENT_SOFT = "rgba(248,63,111,0.16)";
    /** 强调色发光。 */
    public static final String ACCENT_GLOW = "rgba(248,63,111,0.35)";

    /** 成功 / 已连接。 */
    public static final String SUCCESS = "#4cd3a3";
    /** 警告 / 连接中。 */
    public static final String WARNING = "#f0b74a";
    /** 危险 / 错误 / 未连接。 */
    public static final String DANGER = "#ff5c5c";

    /** 功能性信息强调（出装 / 符文 / 技能等）。 */
    public static final String INFO = "#8ab4ff";
}
