package com.lolens.ui.page;

import com.lolens.ui.sidebar.NavPage;
import javafx.scene.Node;

import java.util.Set;

/**
 * 主窗口页面注册表：内容区页面由模块动态注册（如战绩查询页）。
 * 注册表先于 kernel.start() 注入服务层，模块 onLoad 时可拿到并注册页面。
 */
public interface PageRegistry {

    /** 注册一个绑定到指定导航页的内容节点。 */
    void register(NavPage page, Node node);

    /** 已注册页面的导航页集合。 */
    Set<NavPage> pages();

    /** 取指定导航页的节点；未注册时返回 null。 */
    Node node(NavPage page);
}
