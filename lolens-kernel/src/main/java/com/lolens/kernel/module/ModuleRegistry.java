package com.lolens.kernel.module;

import java.util.List;

/** 模块注册表：管理模块生命周期（加载/启用/禁用/卸载）。 */
public interface ModuleRegistry {

    /** 注册一个模块。 */
    void register(LolModule module);

    /** 按 id 查找模块。 */
    LolModule find(String moduleId);

    /** 全部已注册模块（按依赖排序后的顺序）。 */
    List<LolModule> all();

    /** 触发所有模块 onLoad（按依赖排序）。 */
    void loadAll(ModuleContext ctx);

    /** 触发所有模块 onEnable（按依赖排序）。 */
    void enableAll();

    /** 逆序触发所有模块 onDisable。 */
    void disableAll();

    /** 逆序触发所有模块 onUnload。 */
    void unloadAll();
}
