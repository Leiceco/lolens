package com.lolens.kernel.hotkey;

import com.lolens.kernel.event.GamePhase;

import java.util.Optional;
import java.util.Set;

/**
 * 热键注册：内核统一管理，避免多模块争抢冲突。
 */
public interface HotkeyRegistry {

    /**
     * 注册一个热键。
     *
     * @param combo   组合键，如 "Alt+Q"
     * @param scope   生效游戏阶段
     * @param handler 回调
     */
    void register(String combo, Set<GamePhase> scope, Runnable handler);

    /** 检测冲突：已注册的组合键返回注册者信息。 */
    Optional<String> findConflict(String combo);

    /** 注销热键。 */
    void unregister(String combo);

    /** 启动底层键盘钩子。 */
    void start();

    /** 关闭底层键盘钩子。 */
    void shutdown();
}
