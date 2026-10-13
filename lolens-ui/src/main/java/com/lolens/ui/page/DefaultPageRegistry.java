package com.lolens.ui.page;

import com.lolens.ui.sidebar.NavPage;
import javafx.scene.Node;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** 默认页面注册表实现。 */
public class DefaultPageRegistry implements PageRegistry {

    private final Map<NavPage, Node> pages = new EnumMap<>(NavPage.class);

    @Override
    public void register(NavPage page, Node node) {
        if (page != null && node != null) {
            pages.put(page, node);
        }
    }

    @Override
    public Set<NavPage> pages() {
        return Collections.unmodifiableSet(pages.keySet());
    }

    @Override
    public Node node(NavPage page) {
        return pages.get(page);
    }
}
