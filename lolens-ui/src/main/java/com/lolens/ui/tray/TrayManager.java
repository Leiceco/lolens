package com.lolens.ui.tray;

import com.lolens.ui.overlay.OverlayWindow;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;

/**
 * 系统托盘管理：最小化到托盘、托盘菜单显隐叠加窗口与退出应用。
 */
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final OverlayWindow overlay;
    private final Runnable onExit;
    private TrayIcon trayIcon;

    public TrayManager(OverlayWindow overlay) {
        this(overlay, null);
    }

    /** @param onExit 托盘"退出"菜单回调（AWT 线程触发，内部自行切回 FX 线程） */
    public TrayManager(OverlayWindow overlay, Runnable onExit) {
        this.overlay = overlay;
        this.onExit = onExit == null ? () -> {
        } : onExit;
    }

    /** 创建托盘图标（AWT 线程）。 */
    public void install() {
        if (!SystemTray.isSupported()) {
            log.warn("系统不支持托盘");
            return;
        }
        try {
            URL iconUrl = getClass().getResource("/icons/lolens.png");
            java.awt.Image image = iconUrl != null
                    ? ImageIO.read(iconUrl)
                    : java.awt.Toolkit.getDefaultToolkit().createImage(new byte[0]);
            trayIcon = new TrayIcon(image, "LoLens 助手");
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        Platform.runLater(() -> {
                            if (overlay.isVisible()) {
                                overlay.hide();
                            } else {
                                overlay.show();
                            }
                        });
                    }
                }
            });
            // 托盘右键菜单：切换叠加窗口 + 退出（提供完整退出路径，避免只能任务管理器强杀）
            PopupMenu menu = new PopupMenu();
            MenuItem toggleItem = new MenuItem("显示/隐藏叠加窗口");
            toggleItem.addActionListener(e -> Platform.runLater(overlay::toggle));
            MenuItem exitItem = new MenuItem("退出 LoLens");
            exitItem.addActionListener(e -> onExit.run());
            menu.add(toggleItem);
            menu.addSeparator();
            menu.add(exitItem);
            trayIcon.setPopupMenu(menu);
            SystemTray.getSystemTray().add(trayIcon);
            log.info("系统托盘已安装");
        } catch (Exception e) {
            log.warn("托盘安装失败: {}", e.getMessage());
        }
    }

    /** 显示托盘提示。 */
    public void notify(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    public void remove() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }
}
