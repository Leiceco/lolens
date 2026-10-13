package com.lolens.kernel.event;

/** LCU / 游戏数据连接状态变化事件。 */
public record ConnectionStateChanged(ConnectionState from, ConnectionState to) implements GameEvent {
}
