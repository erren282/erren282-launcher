package org.dsh.serverlist;

import javafx.scene.control.Button;
import javafx.event.EventHandler;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.dsh.serverlist.ServerListPlugin;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.QuickPlayOption;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.versions.Versions;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// The "一键进入游戏" flow: the user picks which installed version to launch, and the
/// launcher starts that version directly connecting to the chosen server.
///
/// <p>Launch goes through the launcher's own [Versions#launch] (account selection, version
/// checks and dialogs all reused), injecting a [QuickPlayOption.MultiPlayer] — HMCL
/// translates it to {@code --quickPlayMultiplayer} on 1.20+ and legacy {@code --server}
/// arguments on older versions, so every version works.</p>
public final class ServerLauncher {

    /// The chooser pane currently shown, if any. 进入游戏按钮被双击时 chooseAndLaunch 会被
    /// 连续调用两次：对话框内容面板是一个栈，同一面板被压两次后点「取消」只弹出顶层，
    /// 下面还留着一份一模一样的面板——看起来就是“无法关闭”。以此字段防止重复压栈。
    private static volatile javafx.scene.Node openChooserPane;

    private ServerLauncher() {
    }

    /// Opens the version chooser for `address` (optionally named `serverName`), then launches.
    /// `owner` (the page the chooser was opened from) is blurred for the duration of the
    /// dialog, matching the launcher's native dialog presentation.
    public static void chooseAndLaunch(javafx.scene.Node owner, String address, String serverName) {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            ServerListPlugin.error("进入游戏", "尚未设置游戏目录，请先在启动器中设置游戏目录。");
            return;
        }
        Collection<Version> versions = repository.getVersions();
        if (versions == null || versions.isEmpty()) {
            ServerListPlugin.error("进入游戏", "当前游戏目录没有已安装的游戏版本，请先在「下载」页面安装一个。");
            return;
        }
        // 若上一次的选择器还在打开状态（双击/连点导致重复打开），先将其关闭
        if (openChooserPane != null
                && openChooserPane.getProperties().get(DialogUtils.PROPERTY_PARENT_DIALOG_REF) != null) {
            MenuHelper.closeDialog(openChooserPane);
        }
        openChooserPane = null;

        Label hint = new Label("选择用于连接「" + (serverName == null || serverName.isBlank() ? address : serverName)
                + "」的游戏版本。\n1.20 以上版本使用 QuickPlay，旧版本由启动器自动改用 --server 参数，均可直接进入服务器。");
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-mid-text-color;");
        hint.setWrapText(true);

        // 版本行本身是 JFXButton（ButtonType.FLAT）：点按时的波纹与启动器其他按钮完全一致；
        // 选中的版本用主题色（-monet-primary）圆角框起。
        String idleStyle = "-fx-background-color: transparent; -fx-background-radius: 8;"
                + " -fx-padding: 8 12 8 12; -fx-cursor: hand;"
                + " -fx-alignment: center-left; -fx-label-padding: 0;";
        String selectedStyle = "-fx-background-color: rgba(33,150,243,0.12);"
                + " -fx-border-color: -monet-primary; -fx-border-width: 1.5; -fx-border-radius: 8;"
                + " -fx-padding: 8 12 8 12; -fx-cursor: hand;"
                + " -fx-alignment: center-left; -fx-label-padding: 0;";

        VBox versionRows = new VBox(6);
        final String[] selected = {null};
        final List<com.jfoenix.controls.JFXButton> rowNodes = new ArrayList<>();
        javafx.scene.control.Button launchButton = FXUtils.newRaisedButton("启动并连接");
        launchButton.setDisable(true);
        javafx.scene.control.Button cancelButton = FXUtils.newBorderButton("取消");
        final Runnable[] fireHolder = new Runnable[1];

        List<Version> sorted = versions.stream()
                .sorted(java.util.Comparator.comparing(
                        (Version v) -> v.getId(), Comparator.reverseOrder()))
                .toList();
        for (Version version : sorted) {
            String id = version.getId();
            Label idLabel = new Label(id);
            idLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;"
                    + " -fx-text-fill: -monet-on-surface;");
            idLabel.setMouseTransparent(true);
            String gameVersion = repository.getGameVersion(version).orElse("");
            Label subLabel = new Label(gameVersion.isEmpty() ? "游戏版本未知"
                    : "游戏版本 " + gameVersion);
            subLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");
            subLabel.setMouseTransparent(true);

            VBox rowContent = new VBox(2, idLabel, subLabel);
            com.jfoenix.controls.JFXButton row = new com.jfoenix.controls.JFXButton();
            row.setButtonType(com.jfoenix.controls.JFXButton.ButtonType.FLAT);
            row.setGraphic(rowContent);
            row.setStyle(idleStyle);
            row.setMaxWidth(Double.MAX_VALUE);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            row.setOnMouseClicked(e -> {
                selected[0] = id;
                for (com.jfoenix.controls.JFXButton other : rowNodes)
                    other.setStyle(idleStyle);
                row.setStyle(selectedStyle);
                launchButton.setDisable(false);
                if (e.getClickCount() == 2 && fireHolder[0] != null)
                    fireHolder[0].run();
            });
            rowNodes.add(row);
            versionRows.getChildren().add(row);
        }

        javafx.scene.control.ScrollPane versionScroll =
                new javafx.scene.control.ScrollPane(versionRows);
        versionScroll.setFitToWidth(true);
        versionScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        versionScroll.setPrefHeight(260);
        versionScroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        FXUtils.smoothScrolling(versionScroll);

        VBox pane = new VBox(10, hint, versionScroll,
                new Separator(),
                new HBox(10, cancelButton, launchButton));
        // 与原生对话框完全一致的背景：不透明 surface 色 + 原生内边距（样式类自带）
        pane.getStyleClass().add("jfx-dialog-layout");

        // 对话框打开期间模糊背后的页面，关闭时恢复
        javafx.scene.effect.BoxBlur blur = new javafx.scene.effect.BoxBlur(12, 12, 3);
        Runnable restoreEffect = () -> {
            if (owner != null)
                owner.setEffect(null);
            if (openChooserPane == pane)
                openChooserPane = null;
        };
        if (owner != null)
            owner.setEffect(blur);
        EventHandler<DialogCloseEvent> closeHandler = event -> restoreEffect.run();
        pane.addEventHandler(DialogCloseEvent.CLOSE, closeHandler);

        Runnable fire = () -> {
            if (selected[0] == null)
                return;
            String label = serverName == null || serverName.isBlank() ? address : serverName;
            MenuHelper.closeDialog(pane);
            restoreEffect.run();
            ServerListPlugin.log("启动 " + selected[0] + " 并连接服务器 " + address);
            Versions.launch(repository, selected[0], helper ->
                    helper.setQuickPlayOption(new QuickPlayOption.MultiPlayer(address)));
        };

        fireHolder[0] = fire;
        launchButton.setOnAction(e -> fire.run());
        cancelButton.setOnAction(e -> {
            MenuHelper.closeDialog(pane);
            restoreEffect.run();
        });

        Controllers.dialog(pane);
        openChooserPane = pane;
    }
}
