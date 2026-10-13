package com.lolens.ui;

import com.lolens.kernel.Kernel;
import com.lolens.kernel.config.AppSettings;
import com.lolens.ui.overlay.NativeWindowStyler;
import com.lolens.ui.overlay.OverlayWindow;
import com.lolens.ui.page.DefaultPageRegistry;
import com.lolens.ui.page.PageRegistry;
import com.lolens.ui.panel.DefaultPanelRegistry;
import com.lolens.ui.panel.PanelRegistry;
import com.lolens.ui.tray.TrayManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI 启动器：装配内核与 UI，创建主窗口与叠加窗口。
 *
 * <p>模块注册由 lolens-app 入口负责（通过 {@link #launchWith(Kernel, String[])}
 * 注入已装配的内核），本类不依赖模块层，保持 UI → Kernel 单向依赖。</p>
 */
public class UiLauncher extends Application {

    private static final Logger log = LoggerFactory.getLogger(UiLauncher.class);

    private static Kernel injectedKernel;

    private Kernel kernel;
    private PanelRegistry panels;
    private PageRegistry pageRegistry;
    private TrayManager trayManager;

    /**
     * 应用入口：注入已注册服务与模块的内核，然后启动 JavaFX。
     * 面板注册表必须先于 kernel.start() 注册，模块 onLoad 才能拿到它。
     */
    public static void launchWith(Kernel kernel, String[] args) {
        injectedKernel = kernel;
        launch(args);
    }

    @Override
    public void init() {
        log.info("LoLens UI 初始化...");
        kernel = injectedKernel != null ? injectedKernel : new Kernel();
        panels = new DefaultPanelRegistry();
        pageRegistry = new DefaultPageRegistry();
        kernel.services().register(PanelRegistry.class, panels);
        kernel.services().register(PageRegistry.class, pageRegistry);
        kernel.start();
    }

    @Override
    public void start(Stage primaryStage) {
        OverlayWindow overlay = new OverlayWindow(panels, kernel::currentPhase);
        AppSettings settings = kernel.config().load("app", AppSettings.class, AppSettings.defaults());
        overlay.setBaseOpacity(settings.overlayOpacity());
        DashboardController dashboard = new DashboardController(kernel, overlay);
        dashboard.init(primaryStage);

        // 叠加窗口点击穿透（需 show 之后调用）
        NativeWindowStyler.applyClickThrough(overlay.stage());

        // 托盘（含"退出"菜单项：关闭主窗口/退出时完整释放内核与叠加窗口）
        trayManager = new TrayManager(overlay, () -> Platform.runLater(() -> {
            dashboard.shutdown();
            trayManager.remove();
            Platform.exit();
        }));
        trayManager.install();
        trayManager.notify("LoLens 助手", "已启动，最小化后常驻托盘");

        primaryStage.setOnCloseRequest(e -> {
            // 关闭主窗口即整体退出：释放内核、关闭叠加窗口并结束 JavaFX 运行库，
            // 否则叠加 Stage 常驻会导致 FX 永不退出、进程变成隐形僵尸
            dashboard.shutdown();
            trayManager.remove();
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        if (trayManager != null) {
            trayManager.remove();
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    /** 兜底入口：未注入内核时以默认装配启动（仅用于开发调试）。 */
    public static void main(String[] args) {
        launch(args);
    }
}
