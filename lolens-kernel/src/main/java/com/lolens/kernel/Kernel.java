package com.lolens.kernel;

import com.lolens.kernel.config.ConfigManager;
import com.lolens.kernel.config.JsonConfigManager;
import com.lolens.kernel.event.AsyncEventBus;
import com.lolens.kernel.event.ChampSelectUpdated;
import com.lolens.kernel.event.ConnectionState;
import com.lolens.kernel.event.ConnectionStateChanged;
import com.lolens.kernel.event.EventBus;
import com.lolens.kernel.event.GameDataUpdated;
import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.kernel.event.GamePhaseChanged;
import com.lolens.kernel.hotkey.HotkeyRegistry;
import com.lolens.kernel.hotkey.JNativeHookRegistry;
import com.lolens.kernel.module.DefaultModuleRegistry;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.kernel.module.ModuleRegistry;
import com.lolens.kernel.scheduler.Scheduler;
import com.lolens.kernel.service.DefaultServiceRegistry;
import com.lolens.kernel.service.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LoLens 内核：只负责生命周期、事件、服务注册与全局状态，不包含任何业务逻辑。
 *
 * <p>四层架构中，UI → Modules → Kernel → Services 单向依赖；模块之间不直接依赖，
 * 只通过 {@link EventBus} 和 {@link ServiceRegistry} 通信。</p>
 */
public final class Kernel {

    private static final Logger log = LoggerFactory.getLogger(Kernel.class);

    private final EventBus eventBus;
    private final ServiceRegistry services;
    private final ModuleRegistry modules;
    private final ConfigManager config;
    private final HotkeyRegistry hotkeys;
    private final Scheduler scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    // 全局状态（阶段 / 连接），UI 层可直接读取
    private final AtomicReference<GamePhase> currentPhase = new AtomicReference<>(GamePhase.UNKNOWN);
    private final AtomicReference<ConnectionState> connectionState =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final List<Consumer<GameEvent>> globalListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GamePhase>> phaseListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ConnectionState>> connectionListeners = new CopyOnWriteArrayList<>();
    /** 内核停止时执行的资源释放钩子（如数据采集器、LCU 客户端）。 */
    private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();

    /** 无参构造：供 UI / 入口直接 new Kernel() 使用默认装配。 */
    public Kernel() {
        this(new Builder());
    }

    private Kernel(Builder builder) {
        this.eventBus = builder.eventBus;
        this.services = builder.services;
        this.modules = builder.modules == null ? new DefaultModuleRegistry() : builder.modules;
        this.config = builder.config == null
                ? new JsonConfigManager(Path.of(System.getProperty("user.home"), ".lolens", "config"))
                : builder.config;
        this.hotkeys = builder.hotkeys;
        this.scheduler = new Scheduler();
        wireCoreListeners();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 订阅核心事件，维护全局状态并转发给 UI 监听器。 */
    private void wireCoreListeners() {
        eventBus.subscribe(GamePhaseChanged.class, e -> {
            currentPhase.set(e.to());
            phaseListeners.forEach(l -> l.accept(e.to()));
            globalListeners.forEach(l -> l.accept(e));
        });
        eventBus.subscribe(ConnectionStateChanged.class, e -> {
            connectionState.set(e.to());
            connectionListeners.forEach(l -> l.accept(e.to()));
            globalListeners.forEach(l -> l.accept(e));
        });
        // 叠加窗口需要这些数据事件做面板刷新
        eventBus.subscribe(GameDataUpdated.class, e -> globalListeners.forEach(l -> l.accept(e)));
        eventBus.subscribe(ChampSelectUpdated.class, e -> globalListeners.forEach(l -> l.accept(e)));
    }

    /** 启动内核：先加载模块（onLoad），再启用（onEnable），最后启动调度器与热键。 */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Kernel 已启动，忽略重复 start()");
            return;
        }
        log.info("=== LoLens Kernel 启动 ===");
        if (hotkeys instanceof JNativeHookRegistry jh) {
            jh.setPhaseSupplier(this::currentPhase);
        }
        modules.loadAll(createModuleContext());
        modules.enableAll();
        hotkeys.start();
        log.info("内核启动完成，已加载 {} 个模块", modules.all().size());
    }

    /** 停止内核：按逆序禁用并卸载模块，执行资源释放钩子，停止调度器。 */
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("=== LoLens Kernel 停止 ===");
        modules.disableAll();
        modules.unloadAll();
        // 先执行资源钩子（数据采集器等），再关调度器/事件总线，避免关停后仍向其发布事件
        for (Runnable hook : shutdownHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                log.warn("关闭钩子执行失败", e);
            }
        }
        scheduler.shutdown();
        hotkeys.shutdown();
        if (eventBus instanceof AsyncEventBus asyncEventBus) {
            asyncEventBus.shutdown();
        }
    }

    /** UI 关闭时的别名，语义更直观。 */
    public void shutdown() {
        stop();
    }

    /** 注册模块（须在 start() 之前调用）。 */
    public void registerModule(LolModule module) {
        modules.register(module);
    }

    /** 注册内核停止时的资源释放钩子（须在 stop() 前调用）。 */
    public void addShutdownHook(Runnable hook) {
        if (hook != null) {
            shutdownHooks.add(hook);
        }
    }

    /** 发布事件到全局总线（业务方一般直接用 events()）。 */
    public void publish(GameEvent event) {
        eventBus.publish(event);
    }

    /** 更新游戏阶段并发布 {@link GamePhaseChanged}。 */
    public void updatePhase(GamePhase phase) {
        GamePhase prev = currentPhase.getAndSet(phase);
        if (prev != phase) {
            eventBus.publish(new GamePhaseChanged(prev, phase));
        }
    }

    /** 更新连接状态并发布 {@link ConnectionStateChanged}。 */
    public void updateConnectionState(ConnectionState state) {
        ConnectionState prev = connectionState.getAndSet(state);
        if (prev != state) {
            eventBus.publish(new ConnectionStateChanged(prev, state));
        }
    }

    public GamePhase currentPhase() {
        return currentPhase.get();
    }

    public ConnectionState connectionState() {
        return connectionState.get();
    }

    /** 注册游戏阶段变化监听。 */
    public void onGamePhaseChanged(Consumer<GamePhase> listener) {
        phaseListeners.add(listener);
    }

    /** 注册全局事件监听（叠加窗口面板分发等）。 */
    public void onGameEvent(Consumer<GameEvent> listener) {
        globalListeners.add(listener);
    }

    /** 注册连接状态变化监听。 */
    public void onConnectionChanged(Consumer<ConnectionState> listener) {
        connectionListeners.add(listener);
    }

    private ModuleContext createModuleContext() {
        return new ModuleContext() {
            @Override public EventBus events() { return eventBus; }
            @Override public ServiceRegistry services() { return services; }
            @Override public ConfigManager config() { return config; }
            @Override public HotkeyRegistry hotkeys() { return hotkeys; }
            @Override public Scheduler scheduler() { return scheduler; }
            @Override public Logger logger() {
                return LoggerFactory.getLogger("module");
            }
        };
    }

    public EventBus events() { return eventBus; }
    public ServiceRegistry services() { return services; }
    public ModuleRegistry modules() { return modules; }
    public ConfigManager config() { return config; }
    public HotkeyRegistry hotkeys() { return hotkeys; }
    public Scheduler scheduler() { return scheduler; }

    /** Kernel 构造器。 */
    public static final class Builder {
        private EventBus eventBus = new AsyncEventBus();
        private ServiceRegistry services = new DefaultServiceRegistry();
        private ModuleRegistry modules;
        private ConfigManager config;
        private HotkeyRegistry hotkeys = new JNativeHookRegistry();

        public Builder eventBus(EventBus eventBus) { this.eventBus = eventBus; return this; }
        public Builder services(ServiceRegistry services) { this.services = services; return this; }
        public Builder config(ConfigManager config) { this.config = config; return this; }
        public Builder hotkeys(HotkeyRegistry hotkeys) { this.hotkeys = hotkeys; return this; }

        public Builder configDir(Path dir) {
            this.config = new JsonConfigManager(dir);
            return this;
        }

        public Kernel build() {
            return new Kernel(this);
        }
    }
}
