package com.lolens.kernel.event;

import com.lolens.kernel.module.ModuleState;

/** 模块状态变化事件。 */
public record ModuleStateChanged(String moduleId, ModuleState from, ModuleState to) implements GameEvent {
}
