package com.lolens.kernel.event;

/** 游戏阶段切换事件。 */
public record GamePhaseChanged(GamePhase from, GamePhase to) implements GameEvent {
}
