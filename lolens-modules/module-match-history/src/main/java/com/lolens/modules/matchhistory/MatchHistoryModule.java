package com.lolens.modules.matchhistory;

import com.lolens.kernel.config.AppSettings;
import com.lolens.kernel.module.LolModule;
import com.lolens.kernel.module.ModuleContext;
import com.lolens.ui.page.PageRegistry;
import com.lolens.ui.sidebar.NavPage;

/**
 * 战绩历史查询模块：基于 LCU /lol-match-history/v1/products/lol/ 系列接口，
 * 在主窗口「战绩查询」页展示近期对局列表（模式/英雄/胜负/KDA/时间），点击行查看详情。
 *
 * <p>默认查询当前登录账号；也支持输入其他召唤师名查询（通过 LCU summoner 接口解析 puuid）。
 * 合规边界：仅调用官方 LCU 本地 HTTP API，不读写游戏内存。</p>
 */
public class MatchHistoryModule implements LolModule {

    private final int queryCount;

    private MatchHistoryPage page;

    public MatchHistoryModule() {
        this(20);
    }

    public MatchHistoryModule(int queryCount) {
        this.queryCount = Math.max(1, Math.min(100, queryCount));
    }

    @Override
    public String id() {
        return "match-history";
    }

    @Override
    public String displayName() {
        return "战绩查询";
    }

    @Override
    public void onLoad(ModuleContext ctx) {
        AppSettings settings = ctx.config().load("app", AppSettings.class, AppSettings.defaults());
        this.page = new MatchHistoryPage(ctx, settings.matchHistoryEnabled(), queryCount);
        ctx.services().find(PageRegistry.class).ifPresent(reg -> reg.register(NavPage.MATCH_HISTORY, page.node()));
    }

    @Override
    public void onEnable() {
        // 战绩查询为主窗口页面，无后台轮询任务
    }

    @Override
    public void onDisable() {
        // 页面随导航显示，无需额外资源释放
    }

    @Override
    public void onUnload() {
        // 无订阅需要关闭
    }
}
