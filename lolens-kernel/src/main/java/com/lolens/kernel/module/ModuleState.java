package com.lolens.kernel.module;

/** 模块生命周期状态。 */
public enum ModuleState {
    REGISTERED,
    LOADED,
    ENABLED,
    DISABLED,
    UNLOADED,
    ERROR
}
