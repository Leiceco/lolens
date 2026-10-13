---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 5447a515aadb7c29661893b09b96cc1a_07a78865bfc511f197eb525400393706
    ReservedCode1: xr5aJyO9dVxY8+KAVpT2U5CCYc9A04gq6ft63oUSLVI9SVsN4kqvJW7oa/S9Iceog3h95NnaE+ny2Y+VUoTdFIrEFY+OD6yp32LfgaY7DBTuFXsv91X4Fsv89x1m/KVjvK27zrA4QMbNvxuQa08rfKR5LmqfzxxHnNG5XnHGB1ydpCmYsrs65PX4ElM=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 5447a515aadb7c29661893b09b96cc1a_07a78865bfc511f197eb525400393706
    ReservedCode2: xr5aJyO9dVxY8+KAVpT2U5CCYc9A04gq6ft63oUSLVI9SVsN4kqvJW7oa/S9Iceog3h95NnaE+ny2Y+VUoTdFIrEFY+OD6yp32LfgaY7DBTuFXsv91X4Fsv89x1m/KVjvK27zrA4QMbNvxuQa08rfKR5LmqfzxxHnNG5XnHGB1ydpCmYsrs65PX4ElM=
---

# LoLens 英雄联盟桌面助手

基于需求文档《lol助手.docx》实现的完整 IntelliJ IDEA / Maven 多模块项目。

## 技术栈

- Java 17
- JavaFX 17（UI / 透明叠加窗口）
- Maven 多模块
- Jackson（JSON）
- JNA（Windows 原生窗口控制）
- JNativeHook（全局热键，可选启用）
- Caffeine（本地缓存）
- SLF4J + Logback（日志）

## 架构

四层 + 内核（模块化，模块通过内核 EventBus 解耦）：

```
lolens/
├── lolens-kernel/                    # 内核层：事件总线、模块生命周期、配置、热键、调度、服务注册
├── lolens-services/                  # 数据层：LCU / Live Client / OP.GG / Data Dragon / 缓存
├── lolens-ui/                        # UI 层：主面板、透明叠加窗口、托盘、设置
├── lolens-modules/                   # 模块层（5 个功能模块）
│   ├── lolens-module-core-overlay/           # 核心叠加（热键 HUD）
│   ├── lolens-module-build-recommendation/   # 出装推荐
│   ├── lolens-module-counter-recommendation/ # 对位推荐
│   ├── lolens-module-enemy-stats/            # 敌方战绩
│   └── lolens-module-skill-timer/            # 技能 CD 计时
└── lolens-app/                       # 应用入口 + 资源配置
```

### 数据流

```
英雄联盟客户端(Lockfile) ──► LCU Client ──┐
Live Client Data (2999)  ──► Live API ────┤
OP.GG MCP                ──► OpggClient ──┼──► EventBus ──► 各模块 ──► 叠加窗口面板
Data Dragon              ──► 英雄/图标缓存 ┘
```

### 合规边界

本项目仅通过官方/本地 HTTP API（LCU、Live Client Data、OP.GG、Data Dragon）获取数据，
**不读写游戏内存、不注入 DLL、不修改客户端文件**，符合英雄联盟第三方工具合规要求。

## 模块说明

| 模块 | 功能 | 关键类 |
|---|---|---|
| core-overlay | 热键 HUD、叠加窗口基础设施 | CoreOverlayModule |
| build-recommendation | 实时出装推荐（OP.GG） | BuildRecommendationModule / BuildPanel |
| counter-recommendation | 对位/克制推荐 | CounterRecommendationModule / CounterPanel |
| enemy-stats | 敌方召唤师战绩 | EnemyStatsModule / EnemyStatsPanel |
| skill-timer | 技能 CD 计时（热键标记） | SkillTimerModule / SkillTracker |

## 项目简介

LoLens 是一款英雄联盟桌面辅助工具，基于 Java 17 + JavaFX 17 + Maven 多模块构建。通过官方/本地 HTTP API（LCU、Live Client Data、OP.GG、Data Dragon）获取数据，提供对局内实时出装推荐、对位推荐、敌方战绩、技能 CD 计时等叠加显示功能；不读写游戏内存、不注入 DLL，符合第三方工具合规要求。

## 环境要求

- JDK 17+（推荐 17 或 21）
- Maven 3.6+（本地仓库建议避开系统盘，如 `-Dmaven.repo.local=D:/maven-repo`）
- Windows 10/11（依赖 JNA 原生窗口控制与全局热键）
- 英雄联盟客户端（运行期依赖，见注意事项）

## 打包命令

```bash
mvn clean package
```

打包产物：`lolens-app\target\lolens-app-1.0.0.jar`（约 16.5MB，内含 JavaFX 等全部依赖，可直接运行）。

## 运行方式

- 命令行运行：`java -jar lolens-app/target/lolens-app-1.0.0.jar`
- 双击运行：在资源管理器中直接双击 `lolens-app\target\lolens-app-1.0.0.jar`
- 开发模式运行：`mvn -pl lolens-app javafx:run`

## IDEA 导入与运行入口

1. 打开 IntelliJ IDEA，选择 Open 导入项目根目录 `lolens`，等待 Maven 自动导入 10 个模块。
2. 确认 Project SDK 为 JDK 17（File -> Project Structure -> Project，若缺失请在 `File -> Project Structure -> SDKs` 中添加 JDK 17）。
3. 运行入口：找到 `lolens-app` 模块下的主类 `com.lolens.app.Main`，右键 Run 即可启动。
4. 若 JavaFX 依赖未识别，检查 Maven 仓库路径配置（`Settings -> Build Tools -> Maven`），确保本地仓库指向 `D:\maven-repo` 或重新执行 `mvn clean package` 构建一次。

## 注意事项

- 运行期依赖英雄联盟客户端：需客户端已安装并启动。默认读取 `C:/Riot Games/League of Legends/lockfile`（国际服路径）；国服（WeGame）或自定义安装路径会自动从 `LeagueClientUx.exe` 进程命令行探测，无需额外配置。路径仍可在 `lolens-app/src/main/resources/config/app.properties` 中修改。
- 防火墙放行：本工具通过本机 HTTP API（LCU 端口、Live Client Data 端口 2999）与 OP.GG 外网服务通信，若功能无数据请检查 Windows 防火墙是否拦截 Java 进程，放行后重启工具。
- 对局数据需在客户端设置中开启 Live Client Data（端口 2999）。
- OP.GG 推荐依赖外网连通性，不可用时面板自动降级提示。

## 构建与运行

```bash
# 构建全部模块
mvn clean package

# 运行（JavaFX 应用入口）
mvn -pl lolens-app javafx:run

# 或运行打出的可执行 jar（带依赖）
java -jar lolens-app/target/lolens-app-1.0.0-jar-with-dependencies.jar
```

## 运行前提

- JDK 17+
- 英雄联盟客户端已启动（国际服默认安装在 `C:/Riot Games/League of Legends`；国服/自定义路径会自动探测）
- 对局内数据源为 Live Client Data（端口 2999），需在客户端设置中开启
- OP.GG 推荐数据依赖外网连通性，不可用时面板自动降级提示

## 配置

`lolens-app/src/main/resources/config/app.properties`：

| 配置项 | 说明 | 默认值 |
|---|---|---|
| lcu.lockfile.path | LCU lockfile 路径（国服/自定义路径自动探测） | C:/Riot Games/League of Legends/lockfile |
| opgg.mcp.url | OP.GG MCP 地址 | https://mcp-api.op.gg/mcp |
| overlay.opacity | 叠加窗口透明度 | 0.92 |
| send.cooldown.seconds | 消息发送冷却 | 5 |
| live.poll.interval.seconds | 对局数据轮询间隔 | 5 |

## 热键

| 热键 | 功能 |
|---|---|
| Alt+1 | 记录 Q 技能使用 |
| Alt+2 | 记录 W 技能使用 |
| Alt+3 | 记录 E 技能使用 |
| Alt+4 | 记录 R 技能使用 |
| Alt+5 | 记录召唤师技能 1 使用 |
| Alt+6 | 记录召唤师技能 2 使用 |
| Alt+H | 显隐叠加窗口 |
| Alt+Q | 发送技能 CD 信息到队友（预留） |

## 关键目录

- 事件模型：`lolens-kernel/.../event/`
- 模块 SPI：`lolens-kernel/.../module/`（LolModule、ModuleContext、ModuleDescriptor、ModuleState）
- 服务 SPI：`lolens-kernel/.../service/`
- 叠加面板 SPI：`lolens-ui/.../overlay/OverlayPanel.java`
*（内容由AI生成，仅供参考）*
