package com.lolens.kernel.event;

/**
 * 内核核心事件（游戏状态）。sealed 保证事件类型可控，模块可定义自己的事件。
 */
public sealed interface GameEvent permits
        GamePhaseChanged,
        ChampSelectUpdated,
        GameDataUpdated,
        ConnectionStateChanged,
        ModuleStateChanged {
}
