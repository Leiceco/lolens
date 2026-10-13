package com.lolens.kernel.module;

import com.lolens.kernel.config.ConfigManager;
import com.lolens.kernel.event.EventBus;
import com.lolens.kernel.hotkey.HotkeyRegistry;
import com.lolens.kernel.scheduler.Scheduler;
import com.lolens.kernel.service.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模块上下文默认实现。
 */
public class DefaultModuleContext implements ModuleContext {

    private final LolModule module;
    private final EventBus eventBus;
    private final ConfigManager configManager;
    private final ServiceRegistry services;
    private final HotkeyRegistry hotkeyRegistry;
    private final Scheduler scheduler;

    public DefaultModuleContext(LolModule module,
                                EventBus eventBus,
                                ConfigManager configManager,
                                ServiceRegistry services,
                                HotkeyRegistry hotkeyRegistry,
                                Scheduler scheduler) {
        this.module = module;
        this.eventBus = eventBus;
        this.configManager = configManager;
        this.services = services;
        this.hotkeyRegistry = hotkeyRegistry;
        this.scheduler = scheduler;
    }

    @Override
    public EventBus events() {
        return eventBus;
    }

    @Override
    public ConfigManager config() {
        return configManager;
    }

    @Override
    public ServiceRegistry services() {
        return services;
    }

    @Override
    public HotkeyRegistry hotkeys() {
        return hotkeyRegistry;
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public Logger logger() {
        return LoggerFactory.getLogger("module." + module.id());
    }
}
