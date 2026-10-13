package com.lolens.modules.build;

import com.lolens.services.ddragon.DataDragonService;
import com.lolens.services.ddragon.model.Item;
import com.lolens.services.lcu.LcuPerksService;
import com.lolens.services.opgg.model.ChampionAnalysis;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 出装/天赋主面板页：主窗口「出装/天赋」页，完整展示 OP.GG 推荐出装
 * （出门装 / 核心三件套 / 完整六神装）、符文（主系 + 副系）与技能加点 1-18 级，
 * 数据为空时给出明确提示而非空白。与叠加面板共用同一数据驱动（BuildRecommendationModule）。
 */
public class BuildPageController {

    private static final Set<Integer> STARTER_ITEM_IDS = Set.of(
            1001, 1027, 1028, 1029, 1036, 1042, 1052, 1054, 1055, 1056,
            1057, 1058, 1082, 2003, 2031, 2033, 3340, 3850, 3853, 3854, 3860, 3862, 3863);

    private final VBox root = new VBox(16);
    private final Label titleLabel = new Label("出装 / 天赋推荐");
    private final Label champLabel = new Label("等待选人...");
    private final Label statusLabel = new Label("");
    private final Label emptyLabel = new Label("");
    private final VBox starterBox = new VBox(6);
    private final VBox coreBox = new VBox(6);
    private final VBox fullBox = new VBox(6);
    private final VBox runeBox = new VBox(6);
    private final VBox skillBox = new VBox(6);
    private final Button runeApplyButton = new Button("一键配置天赋");
    private final Label runeApplyStatus = new Label("");

    private final DataDragonService dd;
    private final LcuPerksService perks;

    // 最近一次推荐符文（供点击按钮时使用），由 update() 在 JavaFX 线程写入
    private volatile ChampionAnalysis.RecommendedRune currentRune;
    private volatile String currentChampion = "";

    private static final String ITEM_TEXT =
            "-fx-text-fill: #d8d8e0; -fx-font-size: 13px;";
    private static final String RUNE_TEXT =
            "-fx-text-fill: #8ab4ff; -fx-font-size: 13px;";

    public BuildPageController(DataDragonService dd, LcuPerksService perks) {
        this.dd = dd;
        this.perks = perks;

        root.setPadding(new Insets(24, 32, 24, 24));
        root.getStyleClass().add("build-page");
        titleLabel.getStyleClass().add("page-title");
        champLabel.getStyleClass().add("champ-text");
        statusLabel.getStyleClass().add("data-source-text");
        emptyLabel.getStyleClass().add("empty-text");
        emptyLabel.setWrapText(true);
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);

        runeApplyButton.getStyleClass().add("button");
        runeApplyButton.setOnAction(e -> applyRunes());
        setRuneStatusStyle("status-ok");
        runeApplyStatus.setWrapText(true);
        HBox runeActionRow = new HBox(12);
        runeActionRow.setAlignment(Pos.CENTER_LEFT);
        runeActionRow.getChildren().addAll(runeApplyButton, runeApplyStatus);

        HBox itemRow = new HBox(20);
        itemRow.setAlignment(Pos.TOP_LEFT);
        itemRow.getChildren().addAll(
                section("出门装", starterBox),
                section("核心三件套", coreBox),
                section("完整出装（核心+鞋子）", fullBox));

        root.getChildren().addAll(
                titleLabel,
                champLabel,
                statusLabel,
                emptyLabel,
                itemRow,
                runeActionRow,
                section("符文", runeBox),
                section("技能加点 1-18 级", skillBox));
    }

    private VBox section(String title, VBox box) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label header = new Label(title);
        header.getStyleClass().add("section-title");
        card.getChildren().addAll(header, box);
        return card;
    }

    /** 主面板页节点（可滚动）。 */
    public Node node() {
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setFocusTraversable(false);
        return scroll;
    }

    /** 更新推荐内容（可来自异步线程），与叠加面板接口一致。 */
    public void update(String championName, ChampionAnalysis analysis, List<String> skillOrder,
                       String skillSource, boolean offline) {
        Platform.runLater(() -> {
            currentChampion = championName == null ? "" : championName;
            champLabel.setText(championName + (offline ? " (离线)" : ""));
            statusLabel.setText("数据来源: " + skillSource + (offline ? " · 离线兜底" : ""));
            emptyLabel.setVisible(false);
            emptyLabel.setManaged(false);

            // 技能加点（每 6 级一行，图标 + 字母徽标回退）
            skillBox.getChildren().clear();
            if (skillOrder == null || skillOrder.isEmpty()) {
                skillBox.getChildren().add(new Label("暂无技能加点数据"));
            } else {
                skillBox.getChildren().add(skillRows(skillOrder, analysis.championSlug()));
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

            fillItemBox(starterBox, starters, "暂无出门装推荐");
            fillItemBox(coreBox, cores, "暂无核心装备推荐");
            fillItemBox(fullBox, full, "暂无完整出装推荐");

            // 符文（主系/副系页面图标 + 基石/副符文图标，加载失败回退文本）
            runeBox.getChildren().clear();
            runeApplyStatus.setText("");
            if (analysis.recommendedRunes() == null || analysis.recommendedRunes().isEmpty()) {
                currentRune = null;
                runeBox.getChildren().add(new Label("暂无符文推荐"));
            } else {
                ChampionAnalysis.RecommendedRune rune = analysis.recommendedRunes().get(0);
                currentRune = rune;
                if (rune.name() != null && !rune.name().isBlank()) {
                    runeBox.getChildren().add(runeIconTextCell(
                            dd != null ? dd.runePageIconUrl(rune.name()) : "",
                            "主系: " + rune.name()));
                }
                if (rune.keystone() != null && !rune.keystone().isBlank()) {
                    runeBox.getChildren().add(runeIconTextCell(
                            dd != null ? dd.runeIconUrl(rune.keystone()) : "",
                            "基石: " + rune.keystone()));
                }
                if (rune.secondaryPageName() != null && !rune.secondaryPageName().isBlank()) {
                    runeBox.getChildren().add(runeIconTextCell(
                            dd != null ? dd.runePageIconUrl(rune.secondaryPageName()) : "",
                            "副系: " + rune.secondaryPageName()));
                }
                if (rune.secondaryRunes() != null && !rune.secondaryRunes().isEmpty()) {
                    runeBox.getChildren().add(runeListCell("副系符文: ", rune.secondaryRunes()));
                }
            }

            // 摘要
            if (analysis.summary() != null && !analysis.summary().isBlank()) {
                Label s = new Label("OP.GG 建议: " + analysis.summary());
                s.setWrapText(true);
                s.setStyle("-fx-text-fill: #a0a0b0; -fx-font-size: 12px;");
                runeBox.getChildren().add(s);
            }

            // 完全无数据时的明确提示
            boolean anyData = !items.isEmpty()
                    || (analysis.recommendedRunes() != null && !analysis.recommendedRunes().isEmpty())
                    || (skillOrder != null && !skillOrder.isEmpty());
            if (!anyData) {
                emptyLabel.setText("暂无推荐数据：OP.GG 未返回该英雄的出装/符文/技能信息。"
                        + "请确认网络连通或稍后重试，也可切换到「设置」查看数据源配置。");
                emptyLabel.setVisible(true);
                emptyLabel.setManaged(true);
            }
        });
    }

    /** 进入选人但查询尚未返回时的加载态。 */
    public void showLoading(String championName) {
        Platform.runLater(() -> {
            champLabel.setText(championName);
            statusLabel.setText("正在获取 OP.GG 推荐数据...");
            emptyLabel.setVisible(false);
            emptyLabel.setManaged(false);
            starterBox.getChildren().clear();
            coreBox.getChildren().clear();
            fullBox.getChildren().clear();
            runeBox.getChildren().clear();
            skillBox.getChildren().clear();
        });
    }

    public void reset() {
        Platform.runLater(() -> {
            currentChampion = "";
            currentRune = null;
            champLabel.setText("等待选人...");
            statusLabel.setText("未进入选人阶段：进入英雄选择后自动加载推荐");
            emptyLabel.setText("等待选人阶段：请在英雄选择界面选择英雄后查看出装/天赋推荐。");
            emptyLabel.setVisible(true);
            emptyLabel.setManaged(true);
            starterBox.getChildren().clear();
            coreBox.getChildren().clear();
            fullBox.getChildren().clear();
            runeBox.getChildren().clear();
            runeApplyStatus.setText("");
            skillBox.getChildren().clear();
        });
    }

    /** 一键配置天赋：校验 LCU 连接与阶段后异步写入客户端符文页，状态反馈不阻塞页面。 */
    private void setRuneStatusStyle(String styleClass) {
        runeApplyStatus.getStyleClass().removeAll("status-ok", "status-warn", "status-error");
        runeApplyStatus.getStyleClass().add(styleClass);
    }

    private void applyRunes() {
        ChampionAnalysis.RecommendedRune rune = currentRune;
        String champion = currentChampion;
        if (rune == null || rune.name() == null || rune.name().isBlank()) {
            runeApplyStatus.setText("暂无符文数据，无法配置");
            setRuneStatusStyle("status-warn");
            return;
        }
        if (perks == null || !perks.isConnected()) {
            runeApplyStatus.setText("LCU 未连接，无法写入");
            setRuneStatusStyle("status-warn");
            return;
        }
        if (!perks.isChampionSelect()) {
            runeApplyStatus.setText("仅选人阶段可配置天赋（当前不在选人界面）");
            setRuneStatusStyle("status-warn");
            return;
        }
        runeApplyStatus.setText("正在写入客户端符文页...");
        setRuneStatusStyle("status-warn");
        String pageName = "LoLens·" + (champion == null || champion.isBlank() ? "推荐" : champion);
        new Thread(() -> {
            LcuPerksService.ApplyResult result = perks.apply(pageName, rune);
            Platform.runLater(() -> {
                if (result.success()) {
                    runeApplyStatus.setText("已配置: " + result.pageName()
                            + (result.pageId() != null ? " (页 id " + result.pageId() + ")" : "")
                            + (result.message() != null && !result.message().isBlank() ? " · " + result.message() : ""));
                    setRuneStatusStyle("status-ok");
                } else {
                    runeApplyStatus.setText("配置失败: " + result.message());
                    setRuneStatusStyle("status-error");
                }
            });
        }, "lolens-perks-apply").start();
    }

    private void fillItemBox(VBox box, List<ChampionAnalysis.RecommendedItem> items, String emptyText) {
        box.getChildren().clear();
        if (items.isEmpty()) {
            Label l = new Label("· " + emptyText);
            l.setStyle("-fx-text-fill: #808090; -fx-font-size: 12px;");
            box.getChildren().add(l);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            box.getChildren().add(itemCell(items.get(i), i + 1));
        }
    }

    /** 技能加点：每格显示等级 + 技能图标（失败回退字母徽标），每 6 级一行。 */
    private Node skillRows(List<String> order, String championSlug) {
        VBox lines = new VBox(4);
        for (int start = 0; start < order.size(); start += 6) {
            HBox row = new HBox(8);
            int end = Math.min(start + 6, order.size());
            for (int i = start; i < end; i++) {
                row.getChildren().add(skillCell(championSlug, order.get(i), i + 1));
            }
            lines.getChildren().add(row);
        }
        return lines;
    }

    /** 单个技能格：等级数字 + StackPane（字母徽标为回退层，技能图标覆盖其上，失败自动露出字母）。 */
    private Node skillCell(String championSlug, String spell, int level) {
        Label lv = new Label(String.valueOf(level));
        lv.setStyle("-fx-text-fill: #7a7a86; -fx-font-size: 10px;");

        Label letter = new Label(spell);
        letter.setStyle(spellStyle(spell));
        letter.setMinWidth(22);
        letter.setAlignment(Pos.CENTER);
        letter.setStyle(letter.getStyle() + "; -fx-background-radius: 4; -fx-padding: 2 6 2 6;");

        StackPane pane = new StackPane(letter);
        pane.setAlignment(Pos.CENTER);
        if (dd != null) {
            String url = dd.spellIconUrl(championSlug, abilityIndex(spell));
            if (!url.isBlank()) {
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
                    pane.getChildren().add(icon);
                }
            }
        }
        VBox cell = new VBox(2);
        cell.setAlignment(Pos.CENTER);
        cell.getChildren().addAll(lv, pane);
        return cell;
    }

    private int abilityIndex(String spell) {
        return switch (spell) {
            case "Q" -> 0;
            case "W" -> 1;
            case "E" -> 2;
            case "R" -> 3;
            default -> 0;
        };
    }

    private String spellStyle(String spell) {
        return switch (spell) {
            case "Q" -> "-fx-text-fill: #ff7b7b; -fx-font-weight: bold;";
            case "W" -> "-fx-text-fill: #7bb5ff; -fx-font-weight: bold;";
            case "E" -> "-fx-text-fill: #ffd27b; -fx-font-weight: bold;";
            case "R" -> "-fx-text-fill: #d88bff; -fx-font-weight: bold;";
            default -> "-fx-text-fill: #9fe8b0; -fx-font-weight: bold;";
        };
    }

    /** 符文行：图标 + 文本，图标加载失败自动隐藏（回退纯文本）。 */
    private Node runeIconTextCell(String iconUrl, String text) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        if (iconUrl != null && !iconUrl.isBlank()) {
            ImageView icon = new ImageView();
            icon.setFitWidth(26);
            icon.setFitHeight(26);
            Image img = IconImageCache.get(iconUrl, 26, 26);
            if (img != null) {
                icon.setImage(img);
                img.errorProperty().addListener((obs, ov, err) -> {
                    if (Boolean.TRUE.equals(err)) {
                        icon.setVisible(false);
                    }
                });
                box.getChildren().add(icon);
            }
        }
        Label l = new Label(text);
        l.setStyle(RUNE_TEXT);
        l.setWrapText(true);
        box.getChildren().add(l);
        return box;
    }

    /** 符文列表行：前缀文本 + 每个符文（图标 + 名称）。 */
    private Node runeListCell(String prefix, List<String> runeNames) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        Label prefixLabel = new Label(prefix);
        prefixLabel.setStyle(RUNE_TEXT);
        box.getChildren().add(prefixLabel);
        for (String runeName : runeNames) {
            HBox cell = new HBox(4);
            cell.setAlignment(Pos.CENTER_LEFT);
            if (dd != null) {
                String url = dd.runeIconUrl(runeName);
                if (!url.isBlank()) {
                    ImageView icon = new ImageView();
                    icon.setFitWidth(22);
                    icon.setFitHeight(22);
                    Image img = IconImageCache.get(url, 22, 22);
                    if (img != null) {
                        icon.setImage(img);
                        img.errorProperty().addListener((obs, ov, err) -> {
                            if (Boolean.TRUE.equals(err)) {
                                icon.setVisible(false);
                            }
                        });
                        cell.getChildren().add(icon);
                    }
                }
            }
            Label name = new Label(runeName);
            name.setStyle("-fx-text-fill: #a0a0b0; -fx-font-size: 12px;");
            cell.getChildren().add(name);
            box.getChildren().add(cell);
        }
        return box;
    }

    /** 单件装备：图标（优先 OP.GG 装备 ID 直连，ID 缺失时按名称匹配 item.json；加载失败自动隐藏）+ 顺序 + 名称 + 价格。 */
    private Node itemCell(ChampionAnalysis.RecommendedItem item, int orderNo) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        if (dd != null) {
            String url = resolveItemIconUrl(item);
            if (url != null && !url.isEmpty()) {
                ImageView icon = new ImageView();
                icon.setFitWidth(28);
                icon.setFitHeight(28);
                Image img = IconImageCache.get(url, 28, 28);
                if (img != null) {
                    icon.setImage(img);
                    img.errorProperty().addListener((obs, ov, err) -> {
                        if (Boolean.TRUE.equals(err)) {
                            icon.setVisible(false);
                        }
                    });
                    box.getChildren().add(icon);
                }
            }
        }
        int price = dd != null
                ? dd.item(item.itemId()).map(Item::goldTotal).orElse(0)
                : 0;
        String priceText = price > 0 ? String.valueOf(price) + "g" : "--";
        Label l = new Label(orderNo + ". " + item.name() + "  (" + priceText + ")");
        l.setStyle(ITEM_TEXT);
        box.getChildren().add(l);
        return box;
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
        String name = item.name() == null ? "" : item.name();
        return name.contains("多兰") || name.contains("药水") || name.contains("戒指")
                || name.contains("护符") || name.contains("圣物") || name.contains("尖牙")
                || name.contains("陷阱") || name.contains("守卫");
    }
}
