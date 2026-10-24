package org.dsh.lunarofl;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.dsh.lunarofl.auth.LunarAccounts;
import org.dsh.lunarofl.auth.LunarSessionRefresher;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/// 「Lunar 微软会话」页面：展示 `~/.lunarclient/settings/game/accounts.json` 里各账户的
/// 会话状态，并允许手动触发一次完整刷新。
///
/// <p>正常情况下用户不需要这个页面——Lunar 版本启动时包装类会自动预刷新，游戏运行中
/// 的过期刷新也会走内置的 gameipc 服务。这里主要是排查用：令牌什么时候过期、刷新是否
/// 成功、MSA 刷新令牌是否还活着。</p>
public final class LunarSessionPage extends VBox {

    private final Label statusLabel = new Label(" ");
    private final VBox accountList = new VBox(4);
    private final Button refreshButton;

    public LunarSessionPage() {
        setSpacing(10);
        setPadding(new Insets(12));

        Label title = new Label("Lunar 微软账户会话");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");
        Label subtitle = new Label("Lunar 客户端的多人游戏验证依赖这份账户文件（"
                + LunarAccounts.accountsFile() + "）。"
                + "令牌过期时插件会在启动游戏前自动刷新，游戏运行中也会通过内置的启动器服务"
                + "（gameipc）完成微软验证；这个页面用于查看状态和手动刷新。");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(title, subtitle);

        refreshButton = FXUtils.newRaisedButton("立即刷新会话");
        refreshButton.setOnAction(e -> refreshNow(true));
        Button reloadButton = FXUtils.newRaisedButton("刷新状态");
        reloadButton.setOnAction(e -> reload());

        HBox actions = new HBox(10, refreshButton, reloadButton, statusLabel);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(actions);

        getChildren().add(org.jackhuang.hmcl.ui.construct.ComponentList.createComponentListTitle("账户"));
        getChildren().add(accountList);

        LineButton openFolder = new LineButton();
        openFolder.setTitle("打开账户文件目录");
        openFolder.setSubtitle(LunarAccounts.settingsDirectory().toString());
        openFolder.setOnAction(e -> openDirectory(LunarAccounts.settingsDirectory()));
        org.jackhuang.hmcl.ui.construct.ComponentList folderList = new org.jackhuang.hmcl.ui.construct.ComponentList();
        folderList.getContent().add(openFolder);
        getChildren().add(folderList);

        reload();
    }

    private void reload() {
        accountList.getChildren().clear();
        Path file = LunarAccounts.accountsFile();
        if (!Files.isRegularFile(file)) {
            statusLabel.setText("还没有账户文件（先用官方 Lunar 启动器登录一次，或点「立即刷新会话」前的说明）");
            return;
        }
        try {
            Map<String, Object> root = LunarAccounts.load();
            Map<String, Object> accounts = LunarAccounts.accounts(root);
            if (accounts.isEmpty()) {
                statusLabel.setText("账户文件存在，但里面没有账户");
                return;
            }
            for (Object value : accounts.values()) {
                if (!(value instanceof Map))
                    continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> account = (Map<String, Object>) value;
                accountList.getChildren().add(accountLine(account));
            }
            statusLabel.setText(" ");
        } catch (IOException e) {
            statusLabel.setText("读取账户文件失败：" + e.getMessage());
        }
    }

    private LineButton accountLine(Map<String, Object> account) {
        String name = LunarAccounts.profileName(account);
        if (name == null || name.isBlank())
            name = "(未命名账户)";
        Long exp = LunarAccounts.exp(account);
        String refreshState;
        if (LunarAccounts.refreshToken(account) == null) {
            refreshState = "没有 MSA 刷新令牌（需要在官方启动器里重新登录一次）";
        } else if (exp == null) {
            refreshState = "无法解析令牌有效期";
        } else {
            long remaining = exp - Instant.now().getEpochSecond();
            refreshState = remaining > 0
                    ? "会话有效，剩余 " + formatDuration(remaining) + "（" + Instant.ofEpochSecond(exp) + " 过期）"
                    : "已过期 " + formatDuration(-remaining) + "，启动游戏时会自动刷新";
        }
        LineButton line = new LineButton();
        line.setTitle(name);
        line.setSubtitle(refreshState);
        line.setOnAction(e -> refreshNow(false));
        return line;
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours >= 24) {
            long days = hours / 24;
            return days + " 天 " + (hours % 24) + " 小时";
        }
        if (hours > 0)
            return hours + " 小时 " + minutes + " 分";
        return seconds + " 秒";
    }

    private void refreshNow(boolean force) {
        refreshButton.setDisable(true);
        statusLabel.setText("正在刷新微软会话……");
        Thread worker = new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try {
                LunarSessionRefresher.Result result = LunarSessionRefresher.refreshAll(
                        message -> {
                            synchronized (report) {
                                report.append(message).append(System.lineSeparator());
                            }
                        }, force);
                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    reload();
                    statusLabel.setText("刷新 " + result.refreshed() + " / 跳过 " + result.skipped()
                            + " / 失败 " + result.failed());
                    if (result.failed() > 0)
                        Controllers.dialog(report + System.lineSeparator()
                                        + "刷新失败的账户需要在官方 Lunar 启动器里重新登录一次。",
                                "刷新结果", MessageDialogPane.MessageType.WARNING);
                });
            } catch (IOException e) {
                Logger.LOG.warning("Lunar 会话刷新失败", e);
                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    statusLabel.setText("刷新失败：" + e.getMessage());
                    Controllers.dialog("刷新失败：" + e.getMessage(), "刷新结果",
                            MessageDialogPane.MessageType.ERROR);
                });
            }
        }, "lunar-session-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private static void openDirectory(Path directory) {
        try {
            java.awt.Desktop.getDesktop().open(directory.toFile());
        } catch (Exception e) {
            Controllers.dialog(e.getMessage(), "打开目录失败", MessageDialogPane.MessageType.ERROR);
        }
    }
}
