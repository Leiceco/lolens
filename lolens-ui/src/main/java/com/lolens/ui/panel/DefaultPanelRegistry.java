package com.lolens.ui.panel;

import com.lolens.ui.overlay.OverlayPanel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 默认面板注册表实现。 */
public class DefaultPanelRegistry implements PanelRegistry {

    private final Map<Anchor, List<OverlayPanel>> panels = new EnumMap<>(Anchor.class);

    public DefaultPanelRegistry() {
        for (Anchor anchor : Anchor.values()) {
            panels.put(anchor, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public void register(OverlayPanel panel) {
        register(panel, Anchor.LEFT_BOTTOM);
    }

    @Override
    public void register(OverlayPanel panel, Anchor anchor) {
        panels.get(anchor).add(panel);
        panels.get(anchor).sort((a, b) -> Integer.compare(a.order(), b.order()));
    }

    @Override
    public List<OverlayPanel> all() {
        List<OverlayPanel> result = new ArrayList<>();
        panels.values().forEach(result::addAll);
        return result;
    }

    @Override
    public List<OverlayPanel> byAnchor(Anchor anchor) {
        return panels.get(anchor);
    }
}
