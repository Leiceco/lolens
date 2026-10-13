package com.lolens.kernel.config;

/**
 * 应用级运行期设置（可写，序列化到 config/app.json）。
 * 由设置界面编辑、内核 ConfigManager 持久化；模块启用与否据此决定。
 */
public record AppSettings(
        boolean buildEnabled,
        boolean counterEnabled,
        boolean enemyStatsEnabled,
        boolean skillTimerEnabled,
        boolean autoAcceptEnabled,
        boolean respawnTimerEnabled,
        boolean matchHistoryEnabled,
        boolean pickBanEnabled,
        boolean honorEnabled,
        boolean autoReplyEnabled,
        boolean lobbyFlowEnabled,
        boolean chatStatusEnabled,
        int autoAcceptDelaySeconds,
        double overlayOpacity,
        int sendCooldownSeconds
) {

    /** 默认设置：新增功能默认开启，自动接受默认不延迟。 */
    public static AppSettings defaults() {
        return new AppSettings(true, true, true, true, true, true, true, true,
                true, true, true, true, 0, 0.92, 5);
    }
}
