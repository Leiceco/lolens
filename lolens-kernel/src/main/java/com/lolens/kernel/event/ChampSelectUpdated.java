package com.lolens.kernel.event;

import com.lolens.kernel.model.ChampSelectSession;

/** 选人阶段数据更新事件（LCU champ-select session）。 */
public record ChampSelectUpdated(ChampSelectSession session) implements GameEvent {
}
