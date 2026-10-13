package com.lolens.kernel.hotkey;

import com.lolens.kernel.event.GamePhase;
import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 基于 JNativeHook 的全局热键实现。
 *
 * <p>说明：本实现负责注册/冲突检测/分发；模块调用时须传入「当前游戏阶段」的
 * 提供者，热键仅在其作用阶段内触发回调。</p>
 */
public class JNativeHookRegistry implements HotkeyRegistry, NativeKeyListener {

    private static final Logger log = LoggerFactory.getLogger(JNativeHookRegistry.class);

    /** 当前按下组合键的修饰键状态。 */
    private final Set<Integer> pressedModifiers = ConcurrentHashMap.newKeySet();

    private final Map<String, HotkeyEntry> entries = new ConcurrentHashMap<>();
    private volatile Supplier<GamePhase> phaseSupplier = () -> GamePhase.UNKNOWN;
    private volatile boolean started = false;

    /** 设置游戏阶段提供者（一般由内核或 UI 层注入）。 */
    public void setPhaseSupplier(Supplier<GamePhase> phaseSupplier) {
        this.phaseSupplier = phaseSupplier == null ? () -> GamePhase.UNKNOWN : phaseSupplier;
    }

    @Override
    public synchronized void register(String combo, Set<GamePhase> scope, Runnable handler) {
        if (findConflict(combo).isPresent()) {
            log.warn("热键冲突，忽略注册: {}", combo);
            return;
        }
        entries.put(normalize(combo), new HotkeyEntry(combo, scope, handler));
        log.info("热键已注册: {} (生效阶段: {})", combo, scope);
    }

    @Override
    public Optional<String> findConflict(String combo) {
        String key = normalize(combo);
        return entries.containsKey(key) ? Optional.of(entries.get(key).originCombo) : Optional.empty();
    }

    @Override
    public void unregister(String combo) {
        entries.remove(normalize(combo));
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            started = true;
            log.info("JNativeHook 全局键盘钩子已启动");
        } catch (NativeHookException e) {
            log.error("JNativeHook 启动失败（可能是权限/系统环境问题）", e);
        }
    }

    @Override
    public synchronized void shutdown() {
        if (!started) {
            return;
        }
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.unregisterNativeHook();
        } catch (Exception e) {
            log.warn("JNativeHook 关闭异常: {}", e.getMessage());
        }
        started = false;
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int code = e.getKeyCode();
        if (isModifier(code)) {
            pressedModifiers.add(code);
            return;
        }
        String combo = buildCombo(code);
        GamePhase current = phaseSupplier.get();
        HotkeyEntry entry = entries.get(combo);
        if (entry != null && entry.scope.contains(current)) {
            try {
                entry.handler.run();
            } catch (Exception ex) {
                log.error("热键回调异常 [{}]: {}", combo, ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        pressedModifiers.remove(e.getKeyCode());
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // 无需处理
    }

    private String buildCombo(int keyCode) {
        StringBuilder sb = new StringBuilder();
        if (pressedModifiers.contains(NativeKeyEvent.VC_ALT)) {
            sb.append("Alt+");
        }
        if (pressedModifiers.contains(NativeKeyEvent.VC_CONTROL)) {
            sb.append("Ctrl+");
        }
        if (pressedModifiers.contains(NativeKeyEvent.VC_SHIFT)) {
            sb.append("Shift+");
        }
        sb.append(keyName(keyCode));
        return sb.toString();
    }

    private boolean isModifier(int code) {
        return code == NativeKeyEvent.VC_ALT
                || code == NativeKeyEvent.VC_CONTROL
                || code == NativeKeyEvent.VC_SHIFT
                || code == NativeKeyEvent.VC_META;
    }

    private String keyName(int code) {
        // JNativeHook 键名如 VC_Q -> Q
        return NativeKeyEvent.getKeyText(code)
                .replace("VC_", "")
                .replace("Alt", "")
                .replace("Ctrl", "")
                .trim();
    }

    private String normalize(String combo) {
        return combo == null ? "" : combo.trim().replace(" ", "").toLowerCase();
    }

    private record HotkeyEntry(String originCombo, Set<GamePhase> scope, Runnable handler) {
    }
}
