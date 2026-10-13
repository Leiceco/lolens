package com.lolens.kernel.module;

import java.util.List;

/**
 * 功能模块统一接口。内核通过它管理生命周期。
 *
 * <p>模块实现要点：onLoad 只做注册/订阅，不启动重资源；onEnable 才开始工作；
 * 依赖通过 {@link #dependencies()} 声明，内核据此排序加载。</p>
 */
public interface LolModule {

    /** 模块唯一标识，如 "build-recommendation"。 */
    String id();

    /** 展示名称。 */
    String displayName();

    /** 依赖的其他模块 id（内核据此排序加载）。 */
    default List<String> dependencies() {
        return List.of();
    }

    /** 模块加载：注册服务、订阅事件（不启动重资源）。 */
    void onLoad(ModuleContext ctx);

    /** 模块启用：开始工作。 */
    void onEnable();

    /** 模块禁用：暂停工作，保留状态。 */
    void onDisable();

    /** 模块卸载：释放资源。 */
    default void onUnload() {
    }
}
