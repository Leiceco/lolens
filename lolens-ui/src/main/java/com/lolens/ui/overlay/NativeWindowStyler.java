package com.lolens.ui.overlay;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 原生窗口样式器：通过 JNA 设置点击穿透（WS_EX_TRANSPARENT | WS_EX_LAYERED）与置顶。
 * 仅用于 Windows。其他平台静默降级。
 */
public final class NativeWindowStyler {

    private static final Logger log = LoggerFactory.getLogger(NativeWindowStyler.class);

    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;

    private NativeWindowStyler() {
    }

    /** 启用点击穿透 + 置顶。调用前 stage 必须已 show()。窗口句柄可能在 show 后短暂不可用，做有限次延迟重试。 */
    public static void applyClickThrough(Stage stage) {
        applyClickThrough(stage, 3);
    }

    private static void applyClickThrough(Stage stage, int attempts) {
        try {
            long hwnd = nativeHandle(stage);
            if (hwnd == 0) {
                if (attempts > 1) {
                    // show() 后原生句柄可能尚未就绪，延迟重试（限 3 次，避免无限循环）
                    javafx.animation.PauseTransition wait =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
                    wait.setOnFinished(e -> applyClickThrough(stage, attempts - 1));
                    wait.play();
                } else {
                    log.warn("无法获取窗口句柄，跳过点击穿透");
                }
                return;
            }
            WinDef.HWND h = new WinDef.HWND(new Pointer(hwnd));
            int exStyle = User32.INSTANCE.GetWindowLong(h, GWL_EXSTYLE);
            User32.INSTANCE.SetWindowLong(
                    h,
                    GWL_EXSTYLE,
                    exStyle | WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_TOOLWINDOW
            );
            log.info("叠加窗口已启用点击穿透");
        } catch (Throwable e) {
            log.warn("设置点击穿透失败（非 Windows 平台可忽略）: {}", e.getMessage());
        }
    }

    /**
     * 通过 JavaFX Glass 窗口反射获取原生 HWND（仅 Windows）。
     * 反射链：Window.getPeer() → TKStage.getPlatformWindow() → WinWindow.getNativeHandle()。
     * 返回 0 表示获取失败。
     */
    private static long nativeHandle(Stage stage) {
        try {
            Method getPeer = javafx.stage.Window.class.getDeclaredMethod("getPeer");
            getPeer.setAccessible(true);
            Object tkStage = getPeer.invoke(stage.getScene().getWindow());
            Method getPlatformWindow = tkStage.getClass().getDeclaredMethod("getPlatformWindow");
            getPlatformWindow.setAccessible(true);
            Object platformWindow = getPlatformWindow.invoke(tkStage);
            Method getNativeHandle = platformWindow.getClass().getMethod("getNativeHandle");
            getNativeHandle.setAccessible(true);
            Object handle = getNativeHandle.invoke(platformWindow);
            if (handle instanceof Number n) {
                return n.longValue();
            }
            return 0;
        } catch (Throwable e) {
            return 0;
        }
    }
}
