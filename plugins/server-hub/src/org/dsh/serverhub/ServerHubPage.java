package org.dsh.serverhub;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.plugin.PluginSubPage;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/// 汇聚页内容：把已安装插件发布的子页面渲染成并排的大矩形卡片，点按进入对应功能。
/// 没有子页面时给出安装提示。卡片列表完全是动态的，插件装/卸后重新进入即可看到变化。
///
/// <p>本页还负责窗口左上角返回箭头在“服务器”模块内的路由：停在子页面时先回落地页，
/// 停在落地页时才把返回交还给启动器（弹出导航栈回到主界面）。详见 [#handleBack]。</p>
public final class ServerHubPage extends VBox {

    /// 标题栏返回箭头是全局动作，一个窗口只有一个。第一次进入本模块时把它接管过来，
    /// 之后每次点击都先问当前挂载的汇聚页要不要处理，处理不了再交还给启动器原动作。
    ///
    /// <p>用 static 保存而不是在离开时“还原”现场：`PluginPageProxy` 每次重新进入插件页
    /// 都会新建一个 [ServerHubPage]，新旧实例的挂载/卸载顺序并不固定，逐个还原很容易把
    /// 新实例刚装上的处理器又覆盖掉。这里改成“只装一次、永远问当前活跃实例”，就没有这个
    /// 时序问题。</p>
    private static final Map<Decorator, EventHandler<ActionEvent>> ORIGINAL_BACK = new WeakHashMap<>();
    private static final EventHandler<ActionEvent> NO_OP = event -> {
    };
    private static WeakReference<ServerHubPage> activeHub;

    private final TransitionPane contentPane = new TransitionPane();
    private final VBox landing;

    /// 当前展示的子页面节点（服务器列表 / 服务端管理），null 表示停在落地页。
    private Node currentSubPage;

    public ServerHubPage() {
        setSpacing(0);
        landing = buildLanding();
        // 这里**故意不放滚动容器**：本页是 PluginLauncherPage，启动器会把它包进
        // PluginPageProxy 的 ScrollPane（fitToWidth + FXUtils.smoothScrolling）。
        // 再套一层就会变成两个滚动容器嵌套 —— 滚轮事件被内层吃掉，内层到底后外层
        // 不会接着滚，手感反而更差。落地页与子页面都直接交给外层那一个滚动条。
        contentPane.setContent(landing, ContainerAnimations.NONE);
        getChildren().add(contentPane);

        // 挂到窗口后才找得到 Decorator；离开时（例如切换侧边栏）把“活跃实例”让出来。
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null)
                installBackHandler();
            else
                releaseBackHandler();
        });

        // ESC 与返回箭头保持同一套语义：页面能处理就消费掉，处理不了再让启动器弹栈。
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && handleBack())
                event.consume();
        });
    }

    /// 接管窗口标题栏的返回箭头。
    private void installBackHandler() {
        Decorator decorator = ancestorDecorator(this);
        if (decorator == null)
            return;

        synchronized (ORIGINAL_BACK) {
            if (!ORIGINAL_BACK.containsKey(decorator)) {
                EventHandler<ActionEvent> original = decorator.onBackNavButtonActionProperty().get();
                EventHandler<ActionEvent> fallback = original == null ? NO_OP : original;
                decorator.onBackNavButtonActionProperty().set(event -> {
                    ServerHubPage hub = activeHub == null ? null : activeHub.get();
                    if (hub != null && hub.handleBack())
                        return;
                    fallback.handle(event);
                });
                ORIGINAL_BACK.put(decorator, fallback);
            }
        }
        activeHub = new WeakReference<>(this);
    }

    private void releaseBackHandler() {
        if (activeHub != null && activeHub.get() == this)
            activeHub = null;
    }

    /// 返回箭头 / ESC 的统一入口。
    ///
    /// @return {@code true} 表示本模块已消化这次返回（停留在模块内），
    ///         {@code false} 表示已在落地页，应让启动器弹出导航栈回到主界面
    private boolean handleBack() {
        Node sub = currentSubPage;
        if (sub == null)
            return false;

        // 子页面自己还有子视图（详情 / 表单 / 在线目录）时，先让它退回列表；
        // 它返回 false 即“已在页面内部处理完”，本次返回到此为止。
        if (sub instanceof DecoratorPage page && !page.back())
            return true;

        // 否则退到汇聚页的卡片选择界面。子页面也可能已经通过 backToModule
        // 自己回到了落地页（那样 currentSubPage 已被清空），这里补一次兜底。
        if (currentSubPage != null)
            showLanding();
        return true;
    }

    /// 回到卡片选择界面。
    private void showLanding() {
        currentSubPage = null;
        contentPane.setContent(landing, ContainerAnimations.BACKWARD);
    }

    private static Decorator ancestorDecorator(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof Decorator decorator)
                return decorator;
        }
        return null;
    }

    private VBox buildLanding() {
        VBox landing = new VBox(10);
        landing.setPadding(new Insets(12));

        Label title = new Label("服务器");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");
        Label subtitle = new Label("管理、开设自己的服务端，或浏览社区服务器并一键获取地址");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
        subtitle.setWrapText(true);
        landing.getChildren().add(new VBox(3, title, subtitle));

        HBox cards = new HBox(14);
        int count = 0;
        for (PluginSubPage subPage : PluginMenuService.getSubPages()) {
            Node card;
            try {
                card = bigCard(subPage);
            } catch (Throwable throwable) {
                continue;
            }
            cards.getChildren().add(card);
            count++;
        }

        if (count == 0) {
            Label empty = new Label("还没有可用的服务器功能插件。请安装「服务端管理」或「服务器列表」插件后重新进入本页。");
            empty.setWrapText(true);
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
            empty.setPadding(new Insets(10, 0, 0, 0));
            landing.getChildren().add(empty);
        } else {
            for (Node card : cards.getChildren())
                HBox.setHgrow(card, Priority.ALWAYS);
            landing.getChildren().add(cards);
        }
        return landing;
    }

    /// 一张并排的大矩形入口卡片（JFXButton：点按波纹与启动器原生按钮一致）。
    private Node bigCard(PluginSubPage subPage) {
        javafx.scene.layout.StackPane iconPane = new javafx.scene.layout.StackPane(
                subPage.icon().createIcon());
        iconPane.setPrefSize(56, 56);
        iconPane.setStyle("-fx-background-color: rgba(33,150,243,0.12); -fx-background-radius: 12;");

        Label titleLabel = new Label(subPage.title());
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;"
                + " -fx-text-fill: -monet-on-surface;");
        Label subtitleLabel = new Label(subPage.description());
        subtitleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");
        subtitleLabel.setWrapText(true);
        subtitleLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        subtitleLabel.setMaxWidth(280);

        com.jfoenix.controls.JFXButton card = new com.jfoenix.controls.JFXButton();
        VBox content = new VBox(10, iconPane, titleLabel, subtitleLabel);
        content.setAlignment(Pos.CENTER);
        card.setGraphic(content);
        card.setStyle("-fx-background-color: -monet-surface-container; -fx-background-radius: 12;"
                + " -fx-padding: 20 14 16 14;");
        card.setPrefHeight(190);
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        card.setRipplerFill(javafx.scene.paint.Color.web("#1e88e5", 0.30));
        card.setOnAction(e -> open(subPage));
        return card;
    }

    /// 打开子页面。返回按钮由子页面自己绘制（宿主把「回到汇聚页」的动作传给它），
    /// 这样汇聚页与子页面不会各画一个返回，界面上永远只有一个。
    ///
    /// <p>子页面（服务器列表、服务端管理）是纯 VBox，自己不带滚动容器 —— 这正是
    /// 想要的效果：它们跟着汇聚页一起，由 PluginPageProxy 提供的那**唯一**一个平滑
    /// 滚动条统一滚动，不会出现"页面里再套一个滚动区"。</p>
    private void open(PluginSubPage subPage) {
        Node page;
        try {
            // 传 this::showLanding 而不是内联的 setContent：showLanding 会同步清空
            // currentSubPage，否则返回箭头会以为还停在子页面上。
            page = subPage.pageFactory().apply(this::showLanding);
        } catch (Throwable throwable) {
            Label error = new Label("打开「" + subPage.title() + "」失败：" + throwable);
            error.setWrapText(true);
            page = error;
        }

        currentSubPage = page;
        VBox wrapper = new VBox(8, page);
        wrapper.setPadding(new Insets(12, 12, 2, 12));
        contentPane.setContent(wrapper, ContainerAnimations.FORWARD);
    }
}
