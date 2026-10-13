package com.lolens.modules.build;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.ddragon.model.Item;
import com.lolens.services.lcu.LcuPerksService;
import com.lolens.services.opgg.model.ChampionAnalysis;
import com.lolens.ui.overlay.OverlayPanel;
import com.lolens.ui.overlay.PanelStyles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 出装推荐面板：选人阶段展示 OP.GG 推荐出装顺序（出门装 / 核心三件套 / 完整六神装）
 * 与技能加点顺序（OP.GG 优先，否则本地规则并标注），叠加窗口左下角。
 */
public class BuildPanel implements OverlayPanel {

    private static final Set<Integer> STARTER_ITEM_IDS = Set.of(
            1001, 1027, 1028, 1029, 1036, 1042, 1052, 1054, 1055, 1056,
            1057, 1058, 1082, 2003, 2031, 2033, 3340, 3850, 3853, 3854, 3860, 3862, 3863);

    private final VBox root = new VBox(8);
    private final Label titleLabel = new Label("出装 / 天赋推荐");
    private final Label champLabel = new Label("等待选人...");
    private final Label statusLabel = new Label("");

    // 图2 英雄头部：头像 + 基础信息
    private final ImageView champIconView = new ImageView();
    private final HBox heroHead = new HBox(10);

    // 图2 召唤师技能区块
    private final Label spellSectionLabel = new Label("召唤师技能");
    private final HBox spellBox = new HBox(6);
    private final Label spellSourceLabel = new Label("");

    // 技能加点区块
    private final Label skillLabel = new Label("技能加点");
    private final Label skillValue = new Label("--");

    // 出装顺序区块
    private final Label starterLabel = new Label("出门装");
    private final VBox starterBox = new VBox(2);
    private final Label coreLabel = new Label("核心三件套");
    private final VBox coreBox = new VBox(2);
    private final Label fullLabel = new Label("完整出装（核心+鞋子）");
    private final VBox fullBox = new VBox(2);

    // 符文与摘要区块
    private final VBox extraBox = new VBox(3);

    // 一键配置天赋（紧凑版）
    private final Button runeApplyButton = new Button("一键配置天赋");
    private final Label runeApplyStatus = new Label("");

    private final DataDragonService dd;
    private final LcuPerksService perks;

    private volatile ChampionAnalysis.RecommendedRune currentRune;
    private volatile String currentChampion = "";

    public BuildPanel() {
        this(null, null);
    }

    public BuildPanel(DataDragonService dd) {
        this(dd, null);
    }

    public BuildPanel(DataDragonService dd, LcuPerksService perks) {
        this.dd = dd;
        this.perks = perks;
        root.setStyle(PanelStyles.PANEL_BACKGROUND + "-fx-padding: 10 12 10 12;");
        titleLabel.setStyle(PanelStyles.TITLE);
        champLabel.setStyle("-fx-text-fill: #f2f2f4; -fx-font-size: 15px; -fx-font-weight: bold;");
        statusLabel.setStyle(STATUS_TEXT);
        statusLabel.setWrapText(true);

        skillLabel.setStyle(SECTION_TITLE);
        skillValue.setStyle(SKILL_TEXT);
        skillValue.setWrapText(true);
        starterLabel.setStyle(SECTION_TITLE);
        coreLabel.setStyle(SECTION_TITLE);
        fullLabel.setStyle(SECTION_TITLE);
        spellSectionLabel.setStyle(SECTION_TITLE);
        spellSourceLabel.setStyle(EXTRA_TEXT);
        spellSourceLabel.setWrapText(true);

        // 图2 英雄头部：圆形头像 + 名称/状态
        champIconView.setFitWidth(42);
        champIconView.setFitHeight(42);
        champIconView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 6, 0, 0, 1);");
        VBox champInfo = new VBox(2);
        champInfo.setAlignment(Pos.CENTER_LEFT);
        champInfo.getChildren().addAll(champLabel, statusLabel);
        heroHead.setAlignment(Pos.CENTER_LEFT);
        heroHead.getChildren().addAll(champIconView, champInfo);

        spellBox.setAlignment(Pos.CENTER_LEFT);

        runeApplyButton.setStyle("-fx-background-color: #f83f6f; -fx-text-fill: white;"
                + " -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 5;"
                + " -fx-padding: 3 10 3 10; -fx-cursor: hand;");
        runeApplyButton.setOnAction(e -> applyRunes());
        runeApplyStatus.setStyle(STATUS_TEXT);
        runeApplyStatus.setWrapText(true);
        HBox runeActionRow = new HBox(8);
        runeActionRow.setAlignment(Pos.CENTER_LEFT);
        runeActionRow.getChildren().addAll(runeApplyButton, runeApplyStatus);

        // 图2 深色极简单列卡片流
        root.getChildren().addAll(
                titleLabel, heroHead,
                spellSectionLabel, spellBox, spellSourceLabel,
                skillLabel, skillValue,
                starterLabel, starterBox,
                coreLabel, coreBox,
                fullLabel, fullBox,
                runeActionRow,
                extraBox);
    }

    private static final String SECTION_TITLE =
            "-fx-text-fill: #ffc861; -fx-font-size: 11px; -fx-font-weight: bold;";
    private static final String ITEM_TEXT =
            "-fx-text-fill: #d8d8e0; -fx-font-size: 11px;";
    private static final String SKILL_TEXT =
            "-fx-text-fill: #9fe8b0; -fx-font-size: 12px; -fx-font-weight: bold;";
    private static final String EXTRA_TEXT =
            "-fx-text-fill: #a0a0b0; -fx-font-size: 10px;";
    private static final String STATUS_TEXT =
            "-fx-text-fill: #f0b74a; -fx-font-size: 10px;";

    @Override
    public String id() {
        return "build.recommendation";
    }

    @Override
    public String title() {
        return "出装推荐";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean visibleIn(GamePhase phase) {
        return phase == GamePhase.CHAMP_SELECT
                || phase == GamePhase.LOADING
                || phase == GamePhase.IN_PROGRESS;
    }

    @Override
    public Node buildContent() {
        return root;
    }

    /**
     * 更新推荐内容（可来自异步线程）。图2 深色极简卡片流渲染。
     *
     * @param championName 我方英雄展示名
     * @param championId   我方英雄 id（Data Dragon 头像）
     * @param analysis     英雄分析（出装 / 符文 / 摘要）
     * @param skillOrder   技能加点 1-18 级序列（OP.GG 或本地规则生成）
     * @param skillSource  技能加点来源标注："OP.GG" 或 "本地规则"
     * @param offline      是否离线兜底
     */
    public void update(String championName, int championId, ChampionAnalysis analysis,
                       List<String> skillOrder, String skillSource, boolean offline) {
        Platform.runLater(() -> {
            currentChampion = championName == null ? "" : championName;
            champLabel.setText(currentChampion.isBlank() ? "未知英雄" : currentChampion);
            statusLabel.setText("数据来源: " + skillSource + (offline ? " · 离线兜底" : ""));
            runeApplyStatus.setText("");

            // 图2 英雄头像
            champIconView.setImage(null);
            if (dd != null && championId > 0) {
                Image img = IconImageCache.get(dd.championIconUrl(championId), 42, 42);
                if (img != null) {
                    champIconView.setImage(img);
                }
            }

            // 技能加点：QWER 彩色矩阵（每 6 级一行）
            if (skillOrder == null || skillOrder.isEmpty()) {
                skillLabel.setText("技能加点");
                skillValue.setText("暂无数据");
            } else {
                skillLabel.setText("技能加点 1-18 级（" + skillSource + "）");
                skillValue.setText("");
                skillValue.setGraphic(skillMatrix(skillOrder, analysis.championSlug()));
            }

            // 出装顺序分组
            List<ChampionAnalysis.RecommendedItem> items = analysis.recommendedItems() == null
                    ? List.of() : analysis.recommendedItems();
            List<ChampionAnalysis.RecommendedItem> starters = new ArrayList<>();
            List<ChampionAnalysis.RecommendedItem> majors = new ArrayList<>();
            for (ChampionAnalysis.RecommendedItem item : items) {
                if (isStarter(item)) {
                    starters.add(item);
                } else {
                    majors.add(item);
                }
            }
            List<ChampionAnalysis.RecommendedItem> cores = majors.size() > 3 ? majors.subList(0, 3) : majors;
            // OP.GG 无第四/五/六件字段，完整出装 = 核心三件套 + 鞋子（tier=boots），排除出门装
            List<ChampionAnalysis.RecommendedItem> full = new ArrayList<>(majors);

            starterBox.getChildren().clear();
            starterBox.getChildren().add(itemIconStrip(starters, "暂无出门装推荐"));
            coreBox.getChildren().clear();
            coreBox.getChildren().add(itemIconStrip(cores, "暂无核心装备推荐"));
            fullBox.getChildren().clear();
            fullBox.getChildren().add(itemIconStrip(full, "暂无完整出装推荐"));

            // 符文主系/副系图标序列 + 摘要
            extraBox.getChildren().clear();
            List<ChampionAnalysis.RecommendedRune> runes = analysis.recommendedRunes() == null
                    ? List.of() : analysis.recommendedRunes();
            if (!runes.isEmpty()) {
                ChampionAnalysis.RecommendedRune rune = runes.get(0);
                currentRune = rune;
                extraBox.getChildren().add(runeMainIcons(rune));
                if ((rune.secondaryPageName() != null && !rune.secondaryPageName().isBlank())
                        || (rune.secondaryRunes() != null && !rune.secondaryRunes().isEmpty())) {
                    extraBox.getChildren().add(runeSecondaryIcons(rune));
                }
            } else {
                currentRune = null;
            }
            if (analysis.summary() != null && !analysis.summary().isBlank()) {
                Label s = new Label(analysis.summary());
                s.setWrapText(true);
                s.setStyle(EXTRA_TEXT);
                extraBox.getChildren().add(s);
            }

            // 完全无数据时的明确提示（runes 已在上方判空归一，直接复用避免 NPE）
            boolean anyData = !items.isEmpty() || !runes.isEmpty()
                    || (skillOrder != null && !skillOrder.isEmpty());
            if (!anyData) {
                statusLabel.setText("暂无推荐数据：OP.GG 未返回该英雄的出装/符文/技能信息");
            }
        });
    }

    /** 符文主系图标序列（图2）：页面图标 + 主系符文图标，全失败时文本回退。 */
    private Node runeMainIcons(ChampionAnalysis.RecommendedRune rune) {
        VBox box = new VBox(2);
        Label cap = new Label("主系 · " + (rune.name() == null || rune.name().isBlank() ? "未知" : rune.name()));
        cap.setStyle("-fx-text-fill: #ffc861; -fx-font-size: 10px; -fx-font-weight: bold;");
        HBox icons = new HBox(4);
        icons.setAlignment(Pos.CENTER_LEFT);
        if (dd != null) {
            if (rune.name() != null && !rune.name().isBlank()) {
                addCompactIcon(icons, dd.runePageIconUrl(rune.name()), 28);
            }
            List<String> primaries = rune.primaryRunes() != null && !rune.primaryRunes().isEmpty()
                    ? rune.primaryRunes()
                    : (rune.keystone() == null || rune.keystone().isBlank() ? List.of() : List.of(rune.keystone()));
            for (String n : primaries) {
                addCompactIcon(icons, dd.runeIconUrl(n), 28);
            }
        }
        if (icons.getChildren().isEmpty()) {
            Label r = new Label(runeLine(rune));
            r.setStyle("-fx-text-fill: #8ab4ff; -fx-font-size: 11px;");
            r.setWrapText(true);
            icons.getChildren().add(r);
        }
        box.getChildren().addAll(cap, icons);
        return box;
    }

    /** 符文副系图标序列（图2）：副页面图标 + 副符文图标。 */
    private Node runeSecondaryIcons(ChampionAnalysis.RecommendedRune rune) {
        VBox box = new VBox(2);
        String page = rune.secondaryPageName() == null || rune.secondaryPageName().isBlank()
                ? "未知" : rune.secondaryPageName();
        Label cap = new Label("副系 · " + page);
        cap.setStyle("-fx-text-fill: #ffc861; -fx-font-size: 10px; -fx-font-weight: bold;");
        HBox icons = new HBox(4);
        icons.setAlignment(Pos.CENTER_LEFT);
        if (dd != null) {
            if (rune.secondaryPageName() != null && !rune.secondaryPageName().isBlank()) {
                addCompactIcon(icons, dd.runePageIconUrl(rune.secondaryPageName()), 28);
            }
            if (rune.secondaryRunes() != null) {
                for (String name : rune.secondaryRunes()) {
                    addCompactIcon(icons, dd.runeIconUrl(name), 28);
                }
            }
        }
        if (icons.getChildren().isEmpty()) {
            StringBuilder sb = new StringBuilder("副系");
            if (rune.secondaryPageName() != null && !rune.secondaryPageName().isBlank()) {
                sb.append(": ").append(rune.secondaryPageName());
            }
            if (rune.secondaryRunes() != null && !rune.secondaryRunes().isEmpty()) {
                sb.append(" / ").append(String.join(" + ", rune.secondaryRunes()));
            }
            Label r2 = new Label(sb.toString());
            r2.setStyle("-fx-text-fill: #a0a0b0; -fx-font-size: 10px;");
            r2.setWrapText(true);
            icons.getChildren().add(r2);
        }
        box.getChildren().addAll(cap, icons);
        return box;
    }

    /** 紧凑图标：加载失败自动隐藏。 */
    private void addCompactIcon(HBox box, String url, double size) {
        if (url == null || url.isBlank()) {
            return;
        }
        ImageView icon = new ImageView();
        icon.setFitWidth(size);
        icon.setFitHeight(size);
        Image img = IconImageCache.get(url, size, size);
        if (img == null) {
            return;
        }
        icon.setImage(img);
        img.errorProperty().addListener((obs, ov, err) -> {
            if (Boolean.TRUE.equals(err)) {
                icon.setVisible(false);
            }
        });
        box.getChildren().add(icon);
    }

    /** 符文主行文案：主页面 · 基石 / 副页面。 */
    private String runeLine(ChampionAnalysis.RecommendedRune rune) {
        StringBuilder sb = new StringBuilder("符文: ");
        if (rune.name() != null && !rune.name().isBlank()) {
            sb.append(rune.name());
        }
        if (rune.keystone() != null && !rune.keystone().isBlank()) {
            sb.append(" · ").append(rune.keystone());
        }
        if (rune.secondaryPageName() != null && !rune.secondaryPageName().isBlank()) {
            sb.append(" / 副系 ").append(rune.secondaryPageName());
        }
        return sb.toString();
    }

    /** 技能加点 QWER 彩色矩阵（图2）：每 6 级一行，技能图标 + 彩色字母，末端行高亮 R。 */
    private Node skillMatrix(List<String> order, String championSlug) {
        VBox matrix = new VBox(4);
        int rows = (order.size() + 5) / 6;
        for (int r = 0; r < rows; r++) {
            HBox row = new HBox(4);
            row.setAlignment(Pos.CENTER_LEFT);
            for (int c = 0; c < 6; c++) {
                int idx = r * 6 + c;
                if (idx >= order.size()) {
                    break;
                }
                row.getChildren().add(skillCell(championSlug, order.get(idx), idx + 1));
            }
            matrix.getChildren().add(row);
        }
        return matrix;
    }

    /** 单个加点格：技能图标 + 彩色字母（Q/W/E/R 四色）。 */
    private Node skillCell(String championSlug, String spell, int level) {
        VBox cell = new VBox(1);
        cell.setAlignment(Pos.CENTER);
        if (dd != null && championSlug != null && !championSlug.isBlank()) {
            int ability = abilityIndex(spell);
            if (ability >= 0) {
                String url = dd.spellIconUrl(championSlug, ability);
                if (url != null && !url.isBlank()) {
                    ImageView iv = new ImageView();
                    iv.setFitWidth(24);
                    iv.setFitHeight(24);
                    Image img = IconImageCache.get(url, 24, 24);
                    if (img != null) {
                        iv.setImage(img);
                        img.errorProperty().addListener((obs, ov, err) -> {
                            if (Boolean.TRUE.equals(err)) {
                                iv.setVisible(false);
                            }
                        });
                        cell.getChildren().add(iv);
                    }
                }
            }
        }
        Label letter = new Label(spell == null ? "?" : spell.toUpperCase());
        letter.setMinWidth(22);
        letter.setAlignment(Pos.CENTER);
        letter.setStyle("-fx-text-fill: " + spellColor(spell) + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        Tooltip tip = new Tooltip("第 " + level + " 级升级 " + (spell == null ? "?" : spell.toUpperCase()));
        Tooltip.install(cell, tip);
        cell.getChildren().add(letter);
        return cell;
    }

    /** Q/W/E/R 四色。 */
    private String spellColor(String spell) {
        if (spell == null) {
            return "#a0a0b0";
        }
        return switch (spell.toUpperCase()) {
            case "Q" -> "#4f8df5";
            case "W" -> "#8ad35f";
            case "E" -> "#f5cf4f";
            case "R" -> "#f55f6f";
            default -> "#a0a0b0";
        };
    }

    /** 加点字母 → 技能槽索引（Q=0/W=1/E=2/R=3）。 */
    private int abilityIndex(String spell) {
        if (spell == null) {
            return -1;
        }
        return switch (spell.toUpperCase()) {
            case "Q" -> 0;
            case "W" -> 1;
            case "E" -> 2;
            case "R" -> 3;
            default -> -1;
        };
    }

    private Node emptyHint(String text) {
        Label l = new Label("· " + text);
        l.setStyle("-fx-text-fill: #808090; -fx-font-size: 10px;");
        return l;
    }

    /** 出装图标序列（图2）：图标 + 顺序角标，Tooltip 显示名称/价格，图标失败自动隐藏。 */
    private Node itemIconStrip(List<ChampionAnalysis.RecommendedItem> items, String emptyText) {
        HBox strip = new HBox(5);
        strip.setAlignment(Pos.CENTER_LEFT);
        if (items == null || items.isEmpty()) {
            strip.getChildren().add(emptyHint(emptyText));
            return strip;
        }
        int orderNo = 1;
        for (ChampionAnalysis.RecommendedItem item : items) {
            if (dd == null) {
                continue;
            }
            String url = resolveItemIconUrl(item);
            if (url == null || url.isBlank()) {
                continue;
            }
            VBox cell = new VBox(1);
            cell.setAlignment(Pos.CENTER);
            ImageView icon = new ImageView();
            icon.setFitWidth(30);
            icon.setFitHeight(30);
            Image img = IconImageCache.get(url, 30, 30);
            if (img != null) {
                icon.setImage(img);
                img.errorProperty().addListener((obs, ov, err) -> {
                    if (Boolean.TRUE.equals(err)) {
                        icon.setVisible(false);
                    }
                });
                cell.getChildren().add(icon);
            }
            Label no = new Label(String.valueOf(orderNo++));
            no.setStyle("-fx-text-fill: #808090; -fx-font-size: 9px;");
            cell.getChildren().add(no);
            int price = dd.item(item.itemId()).map(Item::goldTotal).orElse(0);
            String tipText = item.name()
                    + (price > 0 ? " (" + price + "g)" : " (" + item.tier() + ")");
            Tooltip.install(cell, new Tooltip(tipText));
            strip.getChildren().add(cell);
        }
        if (strip.getChildren().isEmpty()) {
            strip.getChildren().add(emptyHint(emptyText));
        }
        return strip;
    }

    /** 装备图标 URL：装备 ID 有效直接走 itemIconUrl；ID 缺失时按名称匹配 item.json 名称链路。 */
    private String resolveItemIconUrl(ChampionAnalysis.RecommendedItem item) {
        if (item.itemId() > 0) {
            return dd.itemIconUrl(item.itemId());
        }
        return dd.itemByName(item.name()).map(Item::id).map(dd::itemIconUrl).orElse("");
    }

    /** 出门装判定：优先按 tier，其次按已知起始装 ID 与名称关键词。 */
    private boolean isStarter(ChampionAnalysis.RecommendedItem item) {
        String tier = item.tier();
        if (tier != null && !tier.isBlank()
                && ("starting".equalsIgnoreCase(tier) || "starter".equalsIgnoreCase(tier))) {
            return true;
        }
        if (STARTER_ITEM_IDS.contains(item.itemId())) {
            return true;
        }
        // 名称兜底只用不会出现在核心装备合成件中的关键词（长剑/短剑等基础件已收入 STARTER_ITEM_IDS，
        // 避免把含这些字样的核心装备误归为出门装）
        String name = item.name() == null ? "" : item.name();
        return name.contains("多兰") || name.contains("药水") || name.contains("戒指")
                || name.contains("护符") || name.contains("圣物") || name.contains("尖牙")
                || name.contains("陷阱") || name.contains("守卫");
    }

    /** 设置召唤师技能（LCU 实时数据或本地兜底）。 */
    public void setSummonerSpells(List<Integer> spellIds, String sourceLabel) {
        Platform.runLater(() -> {
            spellBox.getChildren().clear();
            if (spellIds != null) {
                for (Integer spellId : spellIds) {
                    if (spellId != null && spellId > 0 && dd != null) {
                        String url = dd.summonerSpellIconUrl(spellId);
                        if (url != null && !url.isBlank()) {
                            ImageView iv = new ImageView();
                            iv.setFitWidth(28);
                            iv.setFitHeight(28);
                            Image img = IconImageCache.get(url, 28, 28);
                            if (img != null) {
                                iv.setImage(img);
                                img.errorProperty().addListener((obs, ov, err) -> {
                                    if (Boolean.TRUE.equals(err)) {
                                        iv.setVisible(false);
                                    }
                                });
                                spellBox.getChildren().add(iv);
                            }
                        }
                    }
                }
            }
            spellSourceLabel.setText(sourceLabel == null || sourceLabel.isBlank()
                    ? "" : "来源: " + sourceLabel);
        });
    }

    /** 进入选人但查询尚未返回时的加载态。 */
    public void showLoading(String championName) {
        Platform.runLater(() -> {
            champLabel.setText(championName);
            statusLabel.setText("正在获取 OP.GG 推荐数据...");
            champIconView.setImage(null);
            skillValue.setText("--");
            skillValue.setGraphic(null);
            starterBox.getChildren().clear();
            coreBox.getChildren().clear();
            fullBox.getChildren().clear();
            extraBox.getChildren().clear();
            spellBox.getChildren().clear();
            spellSourceLabel.setText("");
        });
    }

    public void reset() {
        Platform.runLater(() -> {
            currentChampion = "";
            currentRune = null;
            champLabel.setText("等待选人...");
            statusLabel.setText("未进入选人阶段，进入英雄选择后自动加载推荐");
            champIconView.setImage(null);
            skillValue.setText("--");
            skillValue.setGraphic(null);
            starterBox.getChildren().clear();
            coreBox.getChildren().clear();
            fullBox.getChildren().clear();
            extraBox.getChildren().clear();
            spellBox.getChildren().clear();
            spellSourceLabel.setText("");
            runeApplyStatus.setText("");
        });
    }

    /** 一键配置天赋（叠加面板紧凑版）：校验 LCU 连接与阶段后异步写入客户端符文页。 */
    private void applyRunes() {
        ChampionAnalysis.RecommendedRune rune = currentRune;
        String champion = currentChampion;
        if (rune == null || rune.name() == null || rune.name().isBlank()) {
            runeApplyStatus.setText("暂无符文数据，无法配置");
            return;
        }
        if (perks == null || !perks.isConnected()) {
            runeApplyStatus.setText("LCU 未连接，无法写入");
            return;
        }
        if (!perks.isChampionSelect()) {
            runeApplyStatus.setText("仅选人阶段可配置天赋");
            return;
        }
        runeApplyStatus.setText("正在写入客户端符文页...");
        String pageName = "LoLens·" + (champion == null || champion.isBlank() ? "推荐" : champion);
        new Thread(() -> {
            LcuPerksService.ApplyResult result = perks.apply(pageName, rune);
            Platform.runLater(() -> runeApplyStatus.setText(result.success()
                    ? "已配置: " + result.pageName()
                    : "配置失败: " + result.message()));
        }, "lolens-perks-apply").start();
    }

    @Override
    public void onEvent(GameEvent event) {
        // 数据由模块直接驱动
    }
}
