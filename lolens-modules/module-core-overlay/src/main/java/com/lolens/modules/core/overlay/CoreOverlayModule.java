package com.lolens.modules.core.overlay;

import com.lolens.kernel.event.ConnectionStateChanged;
import com.lolens.kernel.event.EventBus.Subscription;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;

/**
 * 核心叠加模块：向叠加窗口注册状态面板，实时展示游戏阶段与 LCU 连接状态。
 */
public class CoreOverlayModule implements LolModule {

    /** 延迟到 onLoad() 构造：JavaFX 组件须在 toolkit 初始化（launch 之后）创建。 */
    private CoreStatusPanel panel;
    private Subscription phaseSub;
    private Subscription connSub;

    @Override
    public String id() {
        return "core-overlay";
    }

    @Override
    public String displayName() {
        return "核心叠加层";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        this.panel = new CoreStatusPanel();
        phaseSub = ctx.events().subscribe(GamePhaseChanged.class, panel::onPhaseChanged);
        connSub = ctx.events().subscribe(ConnectionStateChanged.class, panel::onConnectionChanged);
        ctx.services().find(PanelRegistry.class).ifPresent(reg -> reg.register(panel, Anchor.LEFT_TOP));
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onUnload() {
        if (phaseSub != null) {
            phaseSub.close();
        }
        if (connSub != null) {
            connSub.close();
        }
    }
}
