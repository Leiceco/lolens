package com.lolens.ui.overlay;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import javafx.scene.Node;

/**
 * 叠加面板接口：模块向叠加窗口动态注册的 UI 面板。
 */
public interface OverlayPanel {

    String id();

    String title();

    /** 面板优先级，用于排序（越小越靠前）。 */
    default int order() {
        return 100;
    }

    /** 在哪些游戏阶段可见。 */
    boolean visibleIn(GamePhase phase);

    /** JavaFX 节点。 */
    Node buildContent();

    /** 数据更新回调。 */
    default void onEvent(GameEvent event) {
    }
}
