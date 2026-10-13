package com.lolens.ui.overlay;

import com.lolens.kernel.event.GameEvent;
import com.lolens.kernel.event.GamePhase;
import com.lolens.ui.panel.Anchor;
import com.lolens.ui.panel.PanelRegistry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 透明叠加窗口：JavaFX 全屏透明舞台 + JNA 点击穿透 + 置顶。
 * 叠加窗口本身是空容器，面板由模块动态注册。
 */
public class OverlayWindow {

    private static final Logger log = LoggerFactory.getLogger(OverlayWindow.class);

    private final PanelRegistry panels;
    private final Supplier<GamePhase> phaseSupplier;
    private final Map<Anchor, VBox> anchorBoxes = new EnumMap<>(Anchor.class);
    private final Map<OverlayPanel, javafx.scene.Node> nodes = new java.util.HashMap<>();

    private Stage stage;
    private volatile GamePhase currentPhase = GamePhase.UNKNOWN;
    private volatile boolean visible = false;
    private volatile double baseOpacity = 0.92;

    public OverlayWindow(PanelRegistry panels, Supplier<GamePhase> phaseSupplier) {
        this.panels = panels;
        this.phaseSupplier = phaseSupplier;
    }

    /** 在 JavaFX 启动线程创建叠加窗口。 */
    public void init(Stage stage) {
        this.stage = stage;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("LoLens Overlay");

        // 全屏覆盖主显示器
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMinX());
        stage.setY(screen.getMinY());
        stage.setWidth(screen.getWidth());
        stage.setHeight(screen.getHeight());

        Pane root = new Pane();
        root.setStyle("-fx-background-color: transparent;");
        for (Anchor anchor : Anchor.values()) {
            VBox box = new VBox(6);
            box.setPadding(new Insets(12));
            box.setPickOnBounds(false);
            anchorBoxes.put(anchor, box);
            positionBox(root, box, anchor);
        }
        root.getChildren().addAll(anchorBoxes.values());

        Scene scene = new Scene(root, Color.TRANSPARENT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.show();
        // 初始即显示，透明度应用基础值；visible 与 stage 实际状态保持一致
        stage.setOpacity(baseOpacity);
        visible = true;

        // 注册面板内容
        for (OverlayPanel panel : panels.all()) {
            nodes.put(panel, panel.buildContent());
        }
        refreshVisibility();
    }

    private void positionBox(Pane root, VBox box, Anchor anchor) {
        final double margin = 12;
        switch (anchor) {
            case LEFT_TOP -> {
                box.setLayoutX(margin);
                box.setLayoutY(margin);
            }
            case RIGHT_TOP -> {
                box.layoutXProperty().bind(root.widthProperty().subtract(box.widthProperty()).subtract(margin));
                box.setLayoutY(margin);
            }
            case LEFT_BOTTOM -> {
                box.setLayoutX(margin);
                box.layoutYProperty().bind(root.heightProperty().subtract(box.heightProperty()).subtract(margin));
            }
            case RIGHT_BOTTOM -> {
                box.layoutXProperty().bind(root.widthProperty().subtract(box.widthProperty()).subtract(margin));
                box.layoutYProperty().bind(root.heightProperty().subtract(box.heightProperty()).subtract(margin));
            }
            case CENTER -> {
                box.layoutXProperty().bind(root.widthProperty().subtract(box.widthProperty()).divide(2));
                box.layoutYProperty().bind(root.heightProperty().subtract(box.heightProperty()).divide(2));
            }
        }
    }

    /** 切换游戏阶段时刷新面板可见性。 */
    public void onGamePhaseChanged(GamePhase newPhase) {
        this.currentPhase = newPhase;
        Platform.runLater(this::refreshVisibility);
    }

    /** 把事件分发到各面板（面板可见性只随阶段变化，不在此处全量重建）。 */
    public void dispatch(GameEvent event) {
        Platform.runLater(() -> {
            for (OverlayPanel panel : panels.all()) {
                try {
                    panel.onEvent(event);
                } catch (Exception e) {
                    log.warn("面板事件处理失败 {}: {}", panel.id(), e.getMessage());
                }
            }
        });
    }

    private void refreshVisibility() {
        if (stage == null) {
            return;
        }
        GamePhase phase = phaseSupplier.get();
        anchorBoxes.forEach((anchor, box) -> {
            box.getChildren().clear();
            List<OverlayPanel> anchorPanels = panels.byAnchor(anchor);
            for (OverlayPanel panel : anchorPanels) {
                if (panel.visibleIn(phase)) {
                    javafx.scene.Node node = nodes.get(panel);
                    if (node != null) {
                        box.getChildren().add(node);
                    }
                }
            }
        });
    }

    public void show() {
        visible = true;
        Platform.runLater(() -> stage.setOpacity(baseOpacity));
    }

    public void hide() {
        visible = false;
        Platform.runLater(() -> stage.setOpacity(0.0));
    }

    /** 设置叠加窗口基础不透明度（0.1~1.0），可见时即时生效。 */
    public void setBaseOpacity(double opacity) {
        this.baseOpacity = Math.max(0.1, Math.min(1.0, opacity));
        if (visible) {
            Platform.runLater(() -> stage.setOpacity(baseOpacity));
        }
    }

    public boolean isVisible() {
        return visible;
    }

    /** 切换叠加窗口显隐（Alt+H）。 */
    public void toggle() {
        if (visible) {
            hide();
        } else {
            show();
        }
        log.info("叠加窗口显隐切换: {}", visible ? "显示" : "隐藏");
    }

    /** 关闭叠加窗口（应用退出路径）：不关闭会导致 JavaFX 永不退出、进程僵尸化。 */
    public void shutdown() {
        Stage s = stage;
        if (s != null) {
            Platform.runLater(s::close);
        }
    }

    public Stage stage() {
        return stage;
    }
}
