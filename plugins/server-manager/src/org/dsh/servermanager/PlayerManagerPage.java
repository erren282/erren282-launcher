package org.dsh.servermanager;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/// Manages the players of one server: operators, whitelist and bans.
///
/// <p>The page is a single column of uniform cards: one card per player showing what that player
/// currently is (● 在线 / OP / 白名单 / 封禁) with a button per action on the right, plus the
/// whitelist switch and the add-player field in the same column, same width. Nothing happens by
/// clicking a card's text, so a stray click can never silently remove someone's OP.</p>
///
/// <p>Changes are applied through whichever mechanism the server can actually honour: console
/// commands while it runs (it holds the lists in memory and would overwrite the files on save),
/// and direct file edits while it is stopped — which is also the only way to give a ban an
/// expiry, because the vanilla commands cannot.</p>
@NotNullByDefault
public final class PlayerManagerPage extends VBox {

    /// Units offered by the ban dialog, mapped to the English names used in timestamps.
    private static final String[][] BAN_UNITS = {
            {"分钟", "minute"}, {"小时", "hour"}, {"天", "day"},
            {"周", "week"}, {"月", "month"}, {"年", "year"},
    };

    private final ServerManagerService service;
    private final ServerProfile profile;
    private final Supplier<ServerProcess> processSupplier;

    private final VBox column = new VBox(8);
    private final Label statusLabel = new Label(" ");
    private JFXTextField newPlayerField;

    /// Player names the user has added by hand, kept so an unknown name still gets a row.
    private final Path knownPlayersFile;
    private final Set<String> manualPlayers = new LinkedHashSet<>();

    private ServerPlayers.Snapshot snapshot;

    /// Set while a rebuild is queued, so a burst of console lines causes one rebuild.
    private boolean reloadQueued;

    /// The log listener that keeps this page in step with the running server.
    private final ServerProcess.ServiceEvents.LogListener autoRefresh;

    /// True once the page has been left, so pending rebuilds are dropped.
    private volatile boolean disposed;

    public PlayerManagerPage(ServerManagerService service, ServerProfile profile,
                            Supplier<ServerProcess> processSupplier) {
        this.service = service;
        this.profile = profile;
        this.processSupplier = processSupplier;
        this.knownPlayersFile = service.getDataDirectory().resolve("players.json");

        // Join/leave lines are the only live signal a vanilla server gives, so the page
        // follows the console instead of waiting for the user to click 刷新.
        this.autoRefresh = (process, line) -> {
            if (disposed || process != this.processSupplier.get())
                return;
            if (ServerPlayers.isPlayerPresenceLine(line))
                queueAutoReload();
        };
        ServerProcess.ServiceEvents.addLogListener(autoRefresh);

        setSpacing(12);
        setPadding(new Insets(4, 0, 0, 0));

        // 不再画页内返回按钮：返回统一由窗口左上角的原生箭头（和 ESC）承担，
        // 由 ServerManagerPage#back 决定退回主视图还是汇聚页。
        getChildren().add(ManageUi.heading("玩家管理",
                "管理「" + profile.getName() + "」的玩家权限、白名单与封禁。"
                        + "服务端运行中时改动立即生效；已停止时直接写入配置文件，下次启动生效。"));

        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        statusLabel.setWrapText(true);
        getChildren().add(statusLabel);

        // The column is added directly: the launcher shell already scrolls the whole page, so
        // wrapping it in a ScrollPane would nest two scrollers and the list would only move
        // with the inner one.
        getChildren().add(column);

        reload();
    }

    /// Called by the host page after this node has been added to the scene, so the scroll reset
    /// can find its ancestor scroll pane. Doing it in the constructor cannot work: the node has
    /// no parent there yet.
    void onShown() {
        ManageUi.scrollToTop(this);
    }

    /// Stops the page from reacting to the server console.
    ///
    /// <p>The host page calls this when it navigates away; otherwise this page would keep
    /// receiving every console line and rebuilding a UI that is no longer displayed.</p>
    void dispose() {
        disposed = true;
        ServerProcess.ServiceEvents.removeLogListener(autoRefresh);
    }

    /// Queues one rebuild for the next pulse.
    ///
    /// <p>Debounced rather than per line: a player joining produces several lines
    /// (connect, UUID, join), and rebuilding the column for each one would fight with a
    /// user typing in the add-player field. One rebuild per burst keeps the field usable.</p>
    private void queueAutoReload() {
        if (reloadQueued || disposed)
            return;
        reloadQueued = true;
        Platform.runLater(() -> {
            reloadQueued = false;
            if (disposed)
                return;
            // Keep whatever the user is typing: reload() rebuilds the column, so the text
            // and the caret have to be put back afterwards.
            JFXTextField field = newPlayerField;
            String typed = field == null ? null : field.getText();
            int caret = field == null ? 0 : field.getCaretPosition();
            boolean focused = field != null && field.isFocused();

            reload();

            JFXTextField restored = newPlayerField;
            if (restored != null && typed != null) {
                restored.setText(typed);
                restored.positionCaret(Math.min(caret, typed.length()));
                if (focused)
                    restored.requestFocus();
            }
        });
    }

    /// Re-reads the player state and rebuilds the single column.
    private void reload() {
        column.getChildren().clear();
        snapshot = ServerPlayers.read(profile);
        loadManualPlayers();

        boolean running = service.isRunning(profile);
        List<String> online = ServerPlayers.onlinePlayers(processSupplier.get());
        statusLabel.setText((running ? "● 服务端运行中，改动立即生效" : "○ 服务端已停止，改动写入配置文件")
                + "    在线 " + online.size() + " 人"
                + "    OP " + snapshot.getOps().size()
                + "    白名单 " + snapshot.getWhitelist().size()
                + "    封禁 " + snapshot.getBanned().size());

        // the whitelist switch leads the column, because it gates who can join at all
        buildWhitelistSection();
        buildPlayerSection(online, running);
    }

    // ---------------------------------------------------------- whitelist ---

    /// The whitelist on/off switch, in the same card style as the player rows.
    private void buildWhitelistSection() {
        // read the live value from server.properties, which is what the server actually uses
        boolean enabledNow = ServerPlayers.isWhitelistEnabled(Path.of(profile.getDirectory()));
        profile.setWhitelist(enabledNow);

        column.getChildren().add(ManageUi.entryRow(null,
                enabledNow ? "白名单：已开启" : "白名单：已关闭",
                enabledNow
                        ? "只有名单内的玩家能进入服务器（当前 " + snapshot.getWhitelist().size() + " 人在名单内）"
                        : "当前所有玩家都能进入服务器；开启后只有名单内的玩家能进入",
                ManageUi.primaryButton(enabledNow ? "关闭白名单" : "开启白名单",
                        () -> setWhitelistEnabled(!enabledNow))));
    }

    /// Turns the whitelist on or off for real.
    ///
    /// <p>Three things must agree: the profile (used when writing `server.properties` on the next
    /// start), `server.properties` itself (what the server reads and what this page displays),
    /// and — if the server is already running — the live server via `whitelist on/off`.</p>
    private void setWhitelistEnabled(boolean enabled) {
        profile.setWhitelist(enabled);
        runAsync(enabled ? "开启白名单失败" : "关闭白名单失败", () -> {
            // write it into server.properties right away, so the switch is not silently reverted
            ServerManagerService.writeWhitelistFlag(profile, enabled);

            if (service.isRunning(profile))
                service.sendCommand(profile, ServerPlayers.whitelistToggleCommand(enabled));

            ServerManagerPlugin.log("[玩家管理] 白名单" + (enabled ? "已开启" : "已关闭"));
            Platform.runLater(() -> {
                reload();
                ServerManagerPlugin.info("白名单已" + (enabled ? "开启" : "关闭"),
                        enabled ? "现在只有白名单内的玩家能进入服务器。"
                                : "现在所有玩家都能进入服务器。");
            });
        });
    }

    // ------------------------------------------------------------- players ---

    private void buildPlayerSection(List<String> online, boolean running) {
        // the add-player field, styled as a card in the same column
        newPlayerField = new JFXTextField();
        newPlayerField.setPromptText("玩家名");
        newPlayerField.setOnAction(e -> addPlayer(newPlayerField.getText()));
        column.getChildren().add(ManageUi.inputRow("添加玩家",
                "把玩家加入这个列表，之后就能设置权限、白名单或封禁",
                newPlayerField,
                ManageUi.primaryButton("添加", () -> addPlayer(newPlayerField.getText()))));

        List<String> players = allPlayers(online);
        if (players.isEmpty()) {
            column.getChildren().add(ManageUi.infoCard("还没有玩家",
                    "在上面输入玩家名并点「添加」，或等玩家进服后自动出现在这里。"));
            return;
        }
        for (String name : players)
            column.getChildren().add(buildPlayerRow(name, online.contains(name), running));
    }

    /// One player card: a leading badge icon, state text, and a button per action.
    ///
    /// <p>Actions that would do nothing are shown but disabled, so every row looks the same and
    /// the user never has to hunt for the button that happens to be available.</p>
    private Node buildPlayerRow(String name, boolean isOnline, boolean running) {
        boolean isOp = ServerPlayers.isOp(snapshot, name);
        boolean whitelisted = ServerPlayers.isWhitelisted(snapshot, name);
        ServerPlayers.Ban ban = snapshot.getBanned().stream()
                .filter(entry -> entry.name().equalsIgnoreCase(name)).findFirst().orElse(null);

        StringBuilder badges = new StringBuilder();
        badges.append(isOp ? "OP" : "非 OP");
        badges.append("  ·  ").append(whitelisted ? "在白名单" : "不在白名单");
        if (ban != null)
            badges.append("  ·  已封禁，到期 ").append(ban.expiryText());

        List<Region> actions = new ArrayList<>();
        actions.add(isOp
                ? ManageUi.button("取消 OP", () -> revokeOp(name))
                : ManageUi.button("授予 OP", () -> grantOp(name)));
        actions.add(whitelisted
                ? ManageUi.button("移出白名单", () -> removeWhitelist(name))
                : ManageUi.button("加入白名单", () -> addWhitelist(name)));
        if (ban != null)
            actions.add(ManageUi.primaryButton("解禁", () -> unban(name)));
        else
            actions.add(ManageUi.dangerButton("封禁…", () -> openBanDialog(name)));

        JFXButton kick = ManageUi.button("踢出", () -> kick(name));
        if (!isOnline || !running) {
            kick.setDisable(true);
            kick.setOpacity(0.5);
        }
        actions.add(kick);

        if (manualPlayers.contains(name.toLowerCase(Locale.ROOT)))
            actions.add(ManageUi.button("移除记录", () -> forgetPlayer(name)));

        String title = (isOnline ? "● " : "") + name;
        return ManageUi.entryRow(statusBadge(isOnline, isOp, ban != null),
                title, badges.toString(), actions.toArray(new Region[0]));
    }

    /// A 40×40 leading badge: green for an online OP, blue for online, grey otherwise.
    ///
    /// <p>Built from text rather than an image so it needs no resources and follows the theme.</p>
    private Node statusBadge(boolean online, boolean op, boolean banned) {
        Label badge = new Label(op ? "OP" : online ? "●" : banned ? "✕" : " ");
        badge.setStyle("-fx-alignment: center; -fx-font-size: "
                + (op ? 14 : online ? 22 : 18) + "px; -fx-font-weight: bold;"
                + " -fx-text-fill: -monet-on-primary;"
                + " -fx-background-color: " + (banned ? "-monet-error"
                : op ? "-monet-primary" : online ? "#1a9b4b" : "-monet-surface-container-highest") + ";"
                + " -fx-background-radius: 6; -fx-min-width: 40; -fx-min-height: 40;"
                + " -fx-max-width: 40; -fx-max-height: 40;");
        return badge;
    }

    /// Every player worth showing: the server's own lists, whoever is online, plus names the
    /// user added by hand.
    private List<String> allPlayers(List<String> online) {
        Set<String> names = new LinkedHashSet<>(ServerPlayers.allNames(snapshot));
        names.addAll(online);
        names.addAll(manualPlayers);
        List<String> result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    // ------------------------------------------------------------- actions ---

    private void addPlayer(String rawName) {
        String name = trimName(rawName);
        if (name == null)
            return;
        manualPlayers.add(name.toLowerCase(Locale.ROOT));
        saveManualPlayers();
        if (newPlayerField != null)
            newPlayerField.clear();
        reload();
        ServerManagerPlugin.log("[玩家管理] 已添加玩家 " + name + " 到列表");
    }

    /// Drops a hand-added name from the list. The player's actual state on the server is
    /// untouched — this only stops showing the row.
    private void forgetPlayer(String name) {
        manualPlayers.remove(name.toLowerCase(Locale.ROOT));
        saveManualPlayers();
        reload();
    }

    private void grantOp(String name) {
        if (service.isRunning(profile))
            send(ServerPlayers.opCommand(name), "已授予 " + name + " OP 权限");
        else
            editFiles("授予 OP", () -> ServerPlayers.writeOp(profile, name, 4),
                    "已授予 " + name + " OP 权限（已写入 ops.json，下次启动生效）");
    }

    private void revokeOp(String name) {
        if (service.isRunning(profile))
            send(ServerPlayers.deopCommand(name), "已取消 " + name + " 的 OP");
        else
            editFiles("取消 OP", () -> ServerPlayers.removeOp(profile, name),
                    "已取消 " + name + " 的 OP（已从 ops.json 移除）");
    }

    private void addWhitelist(String name) {
        if (service.isRunning(profile))
            send(ServerPlayers.whitelistAddCommand(name), "已把 " + name + " 加入白名单");
        else
            editFiles("加入白名单", () -> ServerPlayers.addToWhitelist(profile, name),
                    "已把 " + name + " 加入白名单（已写入 whitelist.json）");
    }

    private void removeWhitelist(String name) {
        if (service.isRunning(profile))
            send(ServerPlayers.whitelistRemoveCommand(name), "已把 " + name + " 移出白名单");
        else
            editFiles("移出白名单", () -> ServerPlayers.removeFromWhitelist(profile, name),
                    "已把 " + name + " 移出白名单");
    }

    private void unban(String name) {
        if (service.isRunning(profile))
            send(ServerPlayers.pardonCommand(name), "已解禁 " + name);
        else
            editFiles("解禁", () -> ServerPlayers.removeBan(profile, name), "已解禁 " + name);
    }

    private void kick(String name) {
        if (!service.isRunning(profile)) {
            ServerManagerPlugin.error("踢出玩家", "服务端未在运行。");
            return;
        }
        send(ServerPlayers.kickCommand(name, "Kicked by an operator."), "已把 " + name + " 踢出服务器");
    }

    /// Opens the ban dialog, so the reason and duration do not clutter the row.
    private void openBanDialog(String name) {
        JFXTextField reason = new JFXTextField();
        reason.setPromptText("封禁原因（可留空）");

        JFXTextField amount = new JFXTextField();
        amount.setPromptText("如 7");
        amount.setPrefWidth(70);
        JFXComboBox<String> unit = new JFXComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "分钟", "小时", "天", "周", "月", "年", "永久"));
        unit.getSelectionModel().select("天");

        Label hint = new Label("留空时长或选择「永久」即为永久封禁。");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");

        HBox duration = new HBox(8, new Label("时长"), amount, unit);
        duration.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(10, new Label("将被封禁的玩家：" + name), reason, duration, hint);
        content.setPadding(new Insets(4, 0, 0, 0));

        ManageDialog.show("封禁玩家", content, "确认封禁",
                () -> banPlayer(name, reason.getText(), amount.getText(), unit.getValue()));
    }

    /// Bans a player, optionally for a limited time.
    ///
    /// <p>A temporary ban is written to `banned-players.json` even while the server runs: the
    /// vanilla `ban` command cannot express an expiry, and the server re-reads that file when it
    /// saves, so the entry survives. The player is kicked so the ban applies immediately.</p>
    private void banPlayer(String name, String reason, String amountText, String unit) {
        long amount = 0;
        boolean permanent = unit == null || unit.equals("永久");
        if (!permanent) {
            try {
                amount = amountText == null || amountText.isBlank()
                        ? 0 : Long.parseLong(amountText.trim());
            } catch (NumberFormatException e) {
                ServerManagerPlugin.error("封禁失败", "时长必须是数字，或留空表示永久。");
                return;
            }
            if (amount <= 0)
                permanent = true;
        }

        String expires = permanent ? "forever"
                : ServerPlayers.expiresAfter(amount, englishUnit(unit));
        String finalReason = reason == null || reason.isBlank()
                ? "Banned by an operator." : reason.trim();
        String durationText = permanent ? "永久" : amount + " " + unit;

        editFiles("封禁玩家",
                () -> ServerPlayers.writeBan(profile, name, finalReason, expires),
                "已封禁 " + name + "（" + durationText + "）");

        if (service.isRunning(profile))
            sendQuietly(ServerPlayers.kickCommand(name, finalReason));
    }

    // ------------------------------------------------------------- helpers ---

    /// Validates and normalises a player name typed by the user.
    private String trimName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            ServerManagerPlugin.error("玩家名无效", "请输入玩家名。");
            return null;
        }
        if (!ServerPlayers.isValidName(name)) {
            ServerManagerPlugin.error("玩家名无效",
                    "Minecraft 玩家名只能是 1–16 位字母、数字或下划线：" + name);
            return null;
        }
        return name;
    }

    /// Maps the Chinese unit shown in the UI to the English one used for timestamps.
    private static String englishUnit(String unit) {
        if (unit != null)
            for (String[] pair : BAN_UNITS)
                if (pair[0].equals(unit))
                    return pair[1];
        return "day";
    }

    private void loadManualPlayers() {
        manualPlayers.clear();
        if (!Files.isRegularFile(knownPlayersFile))
            return;
        try {
            String text = Files.readString(knownPlayersFile, StandardCharsets.UTF_8);
            var parsed = com.google.gson.JsonParser.parseString(text);
            if (parsed.isJsonArray())
                for (var element : parsed.getAsJsonArray())
                    if (element.isJsonPrimitive())
                        manualPlayers.add(element.getAsString().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            // a corrupt file simply yields no remembered names
        }
    }

    private void saveManualPlayers() {
        try {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            List<String> sorted = new ArrayList<>(manualPlayers);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            for (String name : sorted)
                array.add(name);
            Files.createDirectories(knownPlayersFile.getParent());
            Files.writeString(knownPlayersFile, array.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            ServerManagerPlugin.error("保存玩家列表失败", e.getMessage());
        }
    }

    /// Sends a console command and reloads afterwards.
    private void send(String command, String successMessage) {
        runAsync("发送命令失败", () -> {
            service.sendCommand(profile, command);
            ServerManagerPlugin.log("[玩家管理] " + command);
            Platform.runLater(() -> {
                reload();
                ServerManagerPlugin.info("完成", successMessage);
            });
        });
    }

    /// Sends a console command without any dialog, for actions already reported elsewhere.
    private void sendQuietly(String command) {
        runAsync("发送命令失败", () -> service.sendCommand(profile, command));
    }

    /// Applies a change to the server's files, then reloads the page.
    private void editFiles(String errorTitle, ThrowingAction action, String successMessage) {
        runAsync(errorTitle, () -> {
            action.run();
            ServerManagerPlugin.log("[玩家管理] " + successMessage);
            Platform.runLater(() -> {
                reload();
                ServerManagerPlugin.info("完成", successMessage);
            });
        });
    }

    private void runAsync(String errorTitle, ThrowingAction task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Platform.runLater(() -> ServerManagerPlugin.error(errorTitle,
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            }
        }, "server-manager-players").start();
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
