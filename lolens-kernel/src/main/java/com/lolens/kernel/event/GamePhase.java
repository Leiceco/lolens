package com.lolens.kernel.event;

/**
 * 游戏阶段状态机。UI 面板按阶段决定可见性，模块按阶段触发逻辑。
 */
public enum GamePhase {
    /** 未知 / 客户端未连接 */
    UNKNOWN,
    /** 大厅 / 客户端主界面 */
    LOBBY,
    /** 匹配中 */
    MATCHMAKING,
    /** 选人阶段 */
    CHAMP_SELECT,
    /** 载入中 */
    LOADING,
    /** 对局进行中 */
    IN_PROGRESS,
    /** 对局结束 / 结算 */
    END_OF_GAME,
    /** 无对局 / 离线 */
    NONE
}
