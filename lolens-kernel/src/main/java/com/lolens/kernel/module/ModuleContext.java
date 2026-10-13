package com.lolens.kernel.module;

import com.lolens.kernel.config.ConfigManager;
import com.lolens.kernel.event.EventBus;
import com.lolens.kernel.hotkey.HotkeyRegistry;
import com.lolens.kernel.scheduler.Scheduler;
import com.lolens.kernel.service.ServiceRegistry;
import org.slf4j.Logger;

/**
 * 模块与内核交互的唯一入口，避免模块直接持有内核引用。
 */
public interface ModuleContext {

    /** 事件订阅/发布。 */
    EventBus events();

    /** 服务查找。 */
    ServiceRegistry services();

    /** 本模块配置命名空间。 */
    ConfigManager config();

    /** 注册热键。 */
    HotkeyRegistry hotkeys();

    /** 定时任务调度（模块内不建议自建线程池，统一走内核调度器）。 */
    Scheduler scheduler();

    /** 带模块前缀的日志。 */
    Logger logger();
}
