package com.lolens.kernel.event;

import com.lolens.kernel.model.LiveGameData;

/** 对局内实时数据更新事件（端口 2999 轮询发布）。 */
public record GameDataUpdated(LiveGameData data) implements GameEvent {
}
