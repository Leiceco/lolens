package com.lolens.ui.panel;

import com.lolens.ui.overlay.OverlayPanel;

import java.util.List;

/**
 * UI 面板注册表：叠加窗口是空容器，面板由模块动态注册。
 */
public interface PanelRegistry {

    /** 注册一个可显示在叠加窗口的面板。 */
    void register(OverlayPanel panel);

    /** 按锚点位置注册（左上/右上/左下/右下/居中）。 */
    void register(OverlayPanel panel, Anchor anchor);

    /** 已注册的全部面板。 */
    List<OverlayPanel> all();

    /** 按锚点获取面板列表。 */
    List<OverlayPanel> byAnchor(Anchor anchor);
}
