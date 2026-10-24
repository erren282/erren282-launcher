/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dsh.mcilm;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXProgressBar;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PromptDialogPane;
import org.jackhuang.hmcl.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The MciLm-link page shown in the multiplayer tab.
///
/// <p>The layout mirrors the launcher's own multiplayer page: one status line with a progress
/// bar, then a short list of large-title rows that changes with the current state. Every input
/// lives in a dialog, so the page only shows what the user can do next.</p>
final class MciLmPage {

    private static final double ICON_SIZE = 24;
    private static final String DOCS_URL = "https://docs.shlm.top/mcilm-link/";

    /// The local ports offered in the settings page.
    private static final List<String> COMMON_PORTS = List.of("25565", "25566", "25567", "25568");

    /// The room sizes a non-sponsor account may host.
    private static final List<String> ROOM_SIZES = List.of("2", "3", "4", "5");
    private static final String SITE_URL = "https://link.mcilm.top";
    private static final String TOKEN_PAGE_URL = SITE_URL + "/user/token";
    private static final String REGISTER_URL = SITE_URL + "/user/login";

    /// What the page is currently doing; drives the whole layout.
    private enum Phase {
        /// The core is not installed.
        SETUP,
        /// Idle: the account may or may not be signed in yet.
        READY,
        /// Starting the core or waiting for the room to become usable.
        CONNECTING,
        /// Connected as host.
        HOSTING,
        /// Connected as guest.
        GUEST,
    }

    private final PluginContext context;
    private final Path dataDirectory;
    private final Path executable;

    private final SimpleStringProperty statusProperty = new SimpleStringProperty();
    private final DoubleProperty progressProperty = new SimpleDoubleProperty(-1);
    private final TransitionPane transition = new TransitionPane();

    private ScrollPane root;
    private Phase phase = Phase.READY;
    private MciLmProcess process;
    private String roomId = "";
    private String roomPassword = "";
    private String lastCoreError = "";
    private boolean busy;

    /// Whether the running session hosts a room (true) or joins one (false).
    private boolean hosting;

    MciLmPage(PluginContext context) {
        this.context = context;
        this.dataDirectory = context.getDataDirectory();
        this.executable = dataDirectory.resolve(MciLmProcess.executableName());
    }

    /// Returns the client executable path the page uses.
    Path getExecutable() {
        return executable;
    }

    /// Builds (or returns) the page node.
    Node create() {
        if (root != null)
            return root;

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().add(ComponentList.createComponentListTitle(Strings.get("mcilm.section.status")));
        content.getChildren().add(transition);
        content.setFillWidth(true);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);

        root = scrollPane;
        refresh();
        return root;
    }

    // ------------------------------------------------------------------ rendering

    /// Rebuilds the row list for the current phase.
    private void refresh() {
        if (transition == null)
            return;

        if (!Files.isRegularFile(executable))
            phase = Phase.SETUP;

        ComponentList components = new ComponentList();
        {
            VBox statusPane = new VBox(8);
            VBox.setMargin(statusPane, new Insets(0, 0, 0, 4));

            Label status = new Label();
            status.textProperty().bind(statusProperty);
            JFXProgressBar progress = new JFXProgressBar();
            progress.progressProperty().bind(progressProperty);
            progress.setMaxWidth(Double.MAX_VALUE);

            statusPane.getChildren().setAll(status, progress);
            components.getContent().add(statusPane);
        }
        components.getContent().addAll(buildRows());

        StackPane.setMargin(components, new Insets(0, 0, 5, 0));
        transition.setContent(components, ContainerAnimations.SLIDE_UP_FADE_IN);
    }

    private List<Node> buildRows() {
        return switch (phase) {
            case SETUP -> buildSetupRows();
            case READY -> buildReadyRows();
            case CONNECTING -> buildConnectingRows();
            case HOSTING -> buildHostingRows();
            case GUEST -> buildGuestRows();
        };
    }

    private List<Node> buildSetupRows() {
        statusProperty.set(Strings.get("mcilm.status.core.missing"));
        progressProperty.set(0);

        TextFlow hint = hintText(Strings.get("mcilm.setup.hint"));

        LineButton download = largeRow(SVG.UPDATE, Strings.get("mcilm.download.core"),
                Strings.get("mcilm.download.core.desc"));
        download.setOnAction(e -> downloadCore());

        LineButton docs = largeRow(SVG.OPEN_IN_NEW, Strings.get("mcilm.docs"), Strings.get("mcilm.docs.desc"));
        docs.setOnAction(e -> FXUtils.openLink(DOCS_URL));

        return List.of(hint, download, docs);
    }

    private List<Node> buildReadyRows() {
        statusProperty.set(signedIn()
                ? Strings.get("mcilm.status.ready")
                : Strings.get("mcilm.status.signed_out"));
        progressProperty.set(1);

        TextFlow hint = hintText(signedIn()
                ? Strings.get("mcilm.ready.hint")
                : Strings.get("mcilm.ready.hint.signed_out"));

        List<Node> rows = new ArrayList<>();

        if (!signedIn()) {
            LineButton login = largeRow(SVG.PUBLIC, Strings.get("mcilm.login"), Strings.get("mcilm.login.desc"));
            login.setOnAction(e -> promptLogin());
            rows.add(login);

            LineButton register = largeRow(SVG.OPEN_IN_NEW, Strings.get("mcilm.register"),
                    Strings.get("mcilm.register.desc"));
            register.setOnAction(e -> FXUtils.openLink(REGISTER_URL));
            rows.add(register);
        } else {
            LineButton host = largeRow(SVG.HOST, Strings.get("mcilm.host"), Strings.get("mcilm.host.desc"));
            host.setOnAction(e -> promptHost());
            rows.add(host);

            LineButton join = largeRow(SVG.ADD_CIRCLE, Strings.get("mcilm.join"), Strings.get("mcilm.join.desc"));
            join.setOnAction(e -> promptJoin());
            rows.add(join);
        }

        LineButton settings = largeRow(SVG.SETTINGS, Strings.get("mcilm.settings"), Strings.get("mcilm.settings.desc"));
        settings.setOnAction(e -> showSettingsDialog());
        rows.add(settings);

        rows.add(0, hint);
        return rows;
    }

    private List<Node> buildConnectingRows() {
        if (busy)
            progressProperty.set(-1);

        TextFlow hint = hintText(Strings.get("mcilm.connecting.hint"));
        LineButton cancel = largeRow(SVG.CLOSE, Strings.get("mcilm.stop"), Strings.get("mcilm.connecting.cancel"));
        cancel.setOnAction(e -> stopProcess());

        return List.of(hint, cancel);
    }

    private List<Node> buildHostingRows() {
        statusProperty.set(Strings.get("mcilm.status.hosting"));
        progressProperty.set(1);

        TextFlow hint = hintText(Strings.get("mcilm.hosting.hint"));

        LineButton code = largeRow(SVG.CONTENT_COPY, Strings.get("mcilm.room.id"), roomId);
        code.setOnAction(e -> copy(roomId));

        LineButton password = largeRow(SVG.CONTENT_COPY, Strings.get("mcilm.room.password"), roomPassword);
        password.setOnAction(e -> copy(roomPassword));

        LineButton stop = largeRow(SVG.CLOSE, Strings.get("mcilm.stop"), Strings.get("mcilm.stop.desc"));
        stop.setOnAction(e -> stopProcess());

        return List.of(hint, code, password, stop);
    }

    private List<Node> buildGuestRows() {
        statusProperty.set(Strings.get("mcilm.status.guest"));
        progressProperty.set(1);

        String port = currentPort();
        TextFlow hint = hintText(Strings.format("mcilm.guest.hint", port));

        LineButton open = largeRow(SVG.ROCKET_LAUNCH, Strings.get("mcilm.guest.enter"),
                Strings.format("mcilm.guest.enter.desc", port));
        open.setOnAction(e -> context.openLink("minecraft://?addExternalServer=MciLm|localhost:" + port));

        LineButton stop = largeRow(SVG.CLOSE, Strings.get("mcilm.stop"), Strings.get("mcilm.stop.desc"));
        stop.setOnAction(e -> stopProcess());

        return List.of(hint, open, stop);
    }

    // ------------------------------------------------------------------ dialogs

    /// Asks for an account/password or a token, then stores the token.
    private void promptLogin() {
        PromptDialogPane.Builder builder = new PromptDialogPane.Builder(
                Strings.get("mcilm.login.prompt"),
                (result, handler) -> {
                    if (result == null || result.size() < 3) {
                        handler.resolve();
                        return;
                    }
                    String account = value(result, 0);
                    String password = value(result, 1);
                    String token = value(result, 2);

                    if (StringUtils.isNotBlank(token)) {
                        handler.resolve();
                        storeText(tokenFile(), token.trim());
                        phase = Phase.READY;
                        refresh();
                        context.showToast(Strings.get("mcilm.token.saved"));
                        return;
                    }
                    if (StringUtils.isBlank(account) || StringUtils.isBlank(password)) {
                        handler.reject(Strings.get("mcilm.login.required"));
                        return;
                    }
                    handler.resolve();
                    login(account, password);
                });

        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.account"), "").setPromptText(Strings.get("mcilm.account.prompt")));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.password"), "").setPromptText(Strings.get("mcilm.password.prompt")));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.token"), "").setPromptText(Strings.get("mcilm.token.prompt")));
        builder.addQuestion(new PromptDialogPane.Builder.HintQuestion(Strings.get("mcilm.login.hint")));

        Controllers.prompt(builder);
    }

    /// Collects the room name, password and port, then starts hosting.
    private void promptHost() {
        String defaultPassword = MciLmProcess.generateRoomPassword();

        PromptDialogPane.Builder builder = new PromptDialogPane.Builder(
                Strings.get("mcilm.host.prompt"),
                (result, handler) -> {
                    if (result == null || result.size() < 3) {
                        handler.resolve();
                        return;
                    }
                    String name = value(result, 0);
                    String password = value(result, 1);
                    String port = value(result, 2);

                    if (StringUtils.isBlank(name)) {
                        handler.reject(Strings.get("mcilm.room.name.required"));
                        return;
                    }
                    if (!name.contains(":") || name.startsWith(":") || name.endsWith(":")) {
                        handler.reject(Strings.get("mcilm.room.name.invalid"));
                        return;
                    }
                    handler.resolve();
                    startHosting(name, password, port);
                });

        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.room.name"), Strings.get("mcilm.room.name.default"))
                .setPromptText(Strings.get("mcilm.room.name.prompt")));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.room.password"), defaultPassword)
                .setPromptText(Strings.get("mcilm.room.password.prompt")));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.port"), "25565"));
        builder.addQuestion(new PromptDialogPane.Builder.HintQuestion(Strings.get("mcilm.host.hint")));

        Controllers.prompt(builder);
    }

    /// Collects the room code and password, then starts joining.
    /// Collects the room code and password, then starts joining.
    ///
    /// <p>No port is asked for: the core syncs the host's forwarded port table automatically,
    /// so a guest only ever connects to <c>localhost:&lt;port&gt;</c> with the port configured in
    /// the settings page.</p>
    private void promptJoin() {
        PromptDialogPane.Builder builder = new PromptDialogPane.Builder(
                Strings.get("mcilm.join.prompt"),
                (result, handler) -> {
                    if (result == null || result.size() < 2) {
                        handler.resolve();
                        return;
                    }
                    String code = value(result, 0);
                    String password = value(result, 1);

                    if (StringUtils.isBlank(code)) {
                        handler.reject(Strings.get("mcilm.join.code.required"));
                        return;
                    }
                    handler.resolve();
                    startJoining(code, password);
                });

        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.join.code"), "").setPromptText("XXXXXXXX-XXXXXXXX-XXXXXXXXXXXXXXXX"));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(
                Strings.get("mcilm.room.password"), "").setPromptText(Strings.get("mcilm.join.password.prompt")));

        Controllers.prompt(builder);
    }

    /// Opens the settings as a native-looking page inside the launcher window.
    ///
    /// <p>Instead of a popup candidate list, this builds the same widgets the launcher uses on
    /// its own settings pages — [ComponentList] sections with [LineButton]/[LineSelectButton]
    /// rows — and shows them in the launcher's dialog layer.</p>
    private void showSettingsDialog() {
        VBox body = new VBox(10);
        body.setFillWidth(true);

        body.getChildren().add(ComponentList.createComponentListTitle(Strings.get("mcilm.settings.section.account")));
        body.getChildren().add(buildAccountSection());

        body.getChildren().add(ComponentList.createComponentListTitle(Strings.get("mcilm.settings.section.connection")));
        body.getChildren().add(buildConnectionSection());

        body.getChildren().add(ComponentList.createComponentListTitle(Strings.get("mcilm.settings.section.about")));
        body.getChildren().add(buildAboutSection());

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(400);
        scrollPane.setMaxHeight(430);
        scrollPane.setPrefWidth(560);
        FXUtils.smoothScrolling(scrollPane);

        // Reuse the launcher's own dialog shell: it supplies the standard background, padding,
        // title bar, Escape handling and buttons that dismiss the popup through DialogCloseEvent.
        // A bare VBox pushed into the dialog container has none of that, which is why the earlier
        // version looked unstyled and could not be closed.
        MessageDialogPane pane = new MessageDialogPane.Builder(
                "", Strings.get("mcilm.settings.prompt"), MessageDialogPane.MessageType.INFO)
                .ok(null)
                .build();

        // The body of MessageDialogPane is [graphic, VBox{title, content, actions}]; swap the
        // text content for the settings page while keeping the title and action row.
        if (pane.getChildren().size() >= 2
                && pane.getChildren().get(1) instanceof VBox outer
                && outer.getChildren().size() >= 3
                && outer.getChildren().get(1) instanceof StackPane contentSlot) {
            contentSlot.getChildren().setAll(scrollPane);
        }

        Controllers.dialog(pane);
    }

    /// Account section: sign in, sign out, open the token page.
    private ComponentList buildAccountSection() {
        ComponentList list = new ComponentList();

        LineButton account = new LineButton();
        account.setTitle(signedIn() ? Strings.get("mcilm.settings.account.signed_in") : Strings.get("mcilm.login"));
        account.setSubtitle(signedIn() ? Strings.get("mcilm.settings.account.signed_in.desc")
                : Strings.get("mcilm.login.desc"));
        account.setTrailingIcon(SVG.ARROW_FORWARD);
        account.setOnAction(e -> promptLogin());
        list.getContent().add(account);

        if (signedIn()) {
            LineButton signOut = new LineButton();
            signOut.setTitle(Strings.get("mcilm.logout"));
            signOut.setSubtitle(Strings.get("mcilm.logout.desc"));
            signOut.setTrailingIcon(SVG.ARROW_FORWARD);
            signOut.setOnAction(e -> {
                signOut();
                showSettingsDialog();
            });
            list.getContent().add(signOut);
        }

        LineButton tokenPage = LineButton.createExternalLinkButton(TOKEN_PAGE_URL);
        tokenPage.setTitle(Strings.get("mcilm.settings.get_token"));
        tokenPage.setSubtitle(Strings.get("mcilm.settings.get_token.desc"));
        list.getContent().add(tokenPage);

        return list;
    }

    /// Connection section: node, local port, room size.
    private ComponentList buildConnectionSection() {
        ComponentList list = new ComponentList();

        LineButton node = new LineButton();
        node.setTitle(Strings.get("mcilm.settings.node"));
        node.setSubtitle(displayNode());
        node.setTrailingIcon(SVG.ARROW_FORWARD);
        node.setOnAction(e -> promptNode());
        list.getContent().add(node);

        LineSelectButton<String> port = new LineSelectButton<>();
        port.setTitle(Strings.get("mcilm.settings.port"));
        port.setSubtitle(Strings.get("mcilm.settings.port.desc"));
        port.setNullSafeConverter(String::valueOf);
        port.setItems(COMMON_PORTS);
        port.setValue(loadPort());
        port.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null)
                storeText(portFile(), newValue);
        });
        list.getContent().add(port);

        LineSelectButton<String> roomMax = new LineSelectButton<>();
        roomMax.setTitle(Strings.get("mcilm.settings.roommax"));
        roomMax.setSubtitle(Strings.get("mcilm.settings.roommax.desc"));
        roomMax.setNullSafeConverter(value -> Strings.format("mcilm.settings.roommax.value", value));
        roomMax.setItems(ROOM_SIZES);
        roomMax.setValue(loadRoomMax());
        roomMax.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null)
                storeText(roomMaxFile(), newValue);
        });
        list.getContent().add(roomMax);

        return list;
    }

    /// About section: core download, data folder, docs.
    private ComponentList buildAboutSection() {
        ComponentList list = new ComponentList();

        LineButton download = new LineButton();
        download.setTitle(Strings.get("mcilm.download.core"));
        download.setSubtitle(Strings.get("mcilm.download.core.desc"));
        download.setTrailingIcon(SVG.ARROW_FORWARD);
        download.setOnAction(e -> downloadCore());
        list.getContent().add(download);

        LineButton folder = new LineButton();
        folder.setTitle(Strings.get("mcilm.open.folder"));
        folder.setSubtitle(dataDirectory.toString());
        folder.setTrailingIcon(SVG.ARROW_FORWARD);
        folder.setOnAction(e -> context.openDirectory(dataDirectory));
        list.getContent().add(folder);

        LineButton docs = LineButton.createExternalLinkButton(DOCS_URL);
        docs.setTitle(Strings.get("mcilm.docs"));
        docs.setSubtitle(Strings.get("mcilm.docs.desc"));
        list.getContent().add(docs);

        return list;
    }

    private void promptNode() {
        Controllers.prompt(Strings.get("mcilm.node.prompt"), (value, handler) -> {
            storeText(nodeFile(), value);
            handler.resolve();
            refresh();
        }, loadNode());
    }

    private void promptPort() {
        Controllers.prompt(Strings.get("mcilm.port.prompt"), (value, handler) -> {
            if (normalizePort(value) == null) {
                handler.reject(Strings.get("mcilm.port.invalid"));
                return;
            }
            storeText(portFile(), normalizePort(value));
            handler.resolve();
            refresh();
        }, loadPort());
    }

    private void promptRoomMax() {
        Controllers.prompt(Strings.get("mcilm.roommax.prompt"), (value, handler) -> {
            try {
                int max = Integer.parseInt(value.trim());
                if (max < 2 || max > 5) {
                    handler.reject(Strings.get("mcilm.roommax.invalid"));
                    return;
                }
                storeText(roomMaxFile(), Integer.toString(max));
                handler.resolve();
                refresh();
            } catch (NumberFormatException e) {
                handler.reject(Strings.get("mcilm.roommax.invalid"));
            }
        }, loadRoomMax());
    }

    // ------------------------------------------------------------------ actions

    private void login(String account, String password) {
        busy = true;
        phase = Phase.CONNECTING;
        statusProperty.set(Strings.get("mcilm.status.logging_in"));
        progressProperty.set(-1);
        refresh();

        Thread thread = new Thread(() -> {
            String token = null;
            String error = null;
            try {
                MciLmApi.Session session = MciLmApi.login(account.trim(), password);
                token = session.apiToken();
                if (token == null || token.isEmpty())
                    token = MciLmApi.fetchApiToken(session.jwt());
            } catch (Throwable e) {
                error = String.valueOf(e.getMessage());
                context.logError("MciLm-link login failed", e);
            }

            String finalToken = token;
            String finalError = error;
            Platform.runLater(() -> {
                busy = false;
                phase = Phase.READY;
                if (finalToken != null && !finalToken.isEmpty()) {
                    storeText(tokenFile(), finalToken);
                    refresh();
                    context.showToast(Strings.get("mcilm.login.success"));
                } else {
                    refresh();
                    context.showError(Strings.get("mcilm.title"),
                            Strings.format("mcilm.login.failed", finalError));
                }
            });
        }, "MciLm-link login");
        thread.setDaemon(true);
        thread.start();
    }

    private void signOut() {
        storeText(tokenFile(), "");
        phase = Phase.READY;
        refresh();
        context.showToast(Strings.get("mcilm.logout.done"));
    }

    private void startHosting(String name, String password, String port) {
        String normalized = normalizePort(port);
        if (normalized == null)
            return;

        roomPassword = StringUtils.isBlank(password) ? MciLmProcess.generateRoomPassword() : password.trim();
        roomId = "";

        List<String> arguments = new ArrayList<>();
        arguments.add("-s");
        arguments.add("online=" + loadRoomMax());
        arguments.add("-n");
        arguments.add(name.trim());
        arguments.add("-p");
        arguments.add(normalized);
        // A room password is what makes the room private; without it the core publishes a
        // public room, which is gated behind the sponsor entitlement.
        arguments.add("-w");
        arguments.add(roomPassword);

        start(arguments, true);
    }

    /// Starts guest mode using the port configured in the settings page.
    ///
    /// @param code     the host's room id
    /// @param password the host's room password, blank for a public room
    private void startJoining(String code, String password) {
        String normalized = normalizePort(loadPort());
        if (normalized == null)
            normalized = "25565";

        List<String> arguments = new ArrayList<>();
        arguments.add("-c");
        arguments.add(code.trim());
        arguments.add("-p");
        arguments.add(normalized);
        if (StringUtils.isNotBlank(password)) {
            arguments.add("-w");
            arguments.add(password.trim());
        }

        start(arguments, false);
    }

    private String normalizePort(String port) {
        String text = StringUtils.isBlank(port) ? "25565" : port.trim();
        try {
            int value = Integer.parseInt(text);
            if (value < 1 || value > 65535)
                throw new NumberFormatException();
            return Integer.toString(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void start(List<String> arguments, boolean host) {
        String token = loadToken();
        String node = loadNode();
        if (StringUtils.isBlank(token) && StringUtils.isBlank(node)) {
            context.showWarning(Strings.get("mcilm.title"), Strings.get("mcilm.login.required"));
            phase = Phase.READY;
            refresh();
            return;
        }
        if (StringUtils.isNotBlank(token)) {
            arguments.add("--token");
            arguments.add(token);
        }
        if (StringUtils.isNotBlank(node)) {
            arguments.add("-a");
            arguments.add(node);
        }
        arguments.add("--json");

        lastCoreError = "";
        busy = true;
        hosting = host;
        phase = Phase.CONNECTING;
        statusProperty.set(host ? Strings.get("mcilm.status.starting.host") : Strings.get("mcilm.status.starting.guest"));
        progressProperty.set(-1);
        refresh();

        MciLmProcess started = new MciLmProcess(executable, arguments);
        started.addEventListener(event -> Platform.runLater(() -> onEvent(event)));
        started.addLogListener(line -> Platform.runLater(() -> onCoreLog(line)));
        started.addExitListener(code -> Platform.runLater(() -> onExit(code, host)));
        process = started;

        Thread starter = new Thread(() -> {
            try {
                started.start();
            } catch (IOException e) {
                context.logError("Failed to start MciLm-link", e);
                Platform.runLater(() -> {
                    busy = false;
                    phase = Phase.READY;
                    refresh();
                    context.showError(Strings.get("mcilm.title"),
                            Strings.format("mcilm.start.failed", String.valueOf(e.getMessage())));
                });
            }
        }, "MciLm-link starter");
        starter.setDaemon(true);
        starter.start();
    }

    /// Handles one NDJSON event from the core.
    ///
    /// <p>The core reports a created room as `{"max_online":5,"room_id":"...","room_meta":"...",
    /// "type":"room"}`; there is no `state` member, so success is detected from `room_id` and
    /// the `type` value rather than from a state string.</p>
    private void onEvent(MciLmProcess.Event event) {
        String raw = event.raw();
        String type = event.type();

        String id = extract(raw, "room_id");
        if (id != null && !id.isEmpty()) {
            boolean first = !id.equals(roomId);
            roomId = id;
            busy = false;
            phase = Phase.HOSTING;
            if (first)
                refresh();
            return;
        }

        if ("room".equals(type) || type.contains("ready") || type.contains("connected")) {
            busy = false;
            if (phase == Phase.CONNECTING) {
                phase = hosting ? Phase.HOSTING : Phase.GUEST;
                refresh();
            }
        }

        if (type.contains("error")) {
            statusProperty.set(Strings.get("mcilm.status.failed"));
        }
    }

    private void onCoreLog(String line) {
        String plain = line.replaceAll("\u001B\\[[;\\d]*m", "");
        if (plain.contains("ERROR") || plain.contains("错误"))
            lastCoreError = plain.trim();
        context.logInfo("[core] " + plain);

        // The core prints its progress on stderr; use it as a fallback so the page leaves the
        // "connecting" phase even when the event stream carries no explicit state.
        if (phase == Phase.CONNECTING) {
            if (!hosting && plain.contains("已连接")) {
                busy = false;
                phase = Phase.GUEST;
                refresh();
            } else if (plain.contains("等待客户端连接")) {
                // Host is up once the core waits for guests, even before the event arrives.
                if (!roomId.isEmpty()) {
                    busy = false;
                    phase = Phase.HOSTING;
                    refresh();
                }
            }
        }
    }

    private void onExit(int code, boolean host) {
        process = null;
        busy = false;

        if (code == 0) {
            phase = Phase.READY;
            refresh();
            context.showToast(host ? Strings.get("mcilm.status.room.closed") : Strings.get("mcilm.status.left"));
            return;
        }

        phase = Phase.READY;
        refresh();
        String reason = lastCoreError.isEmpty() ? Strings.get("mcilm.status.failed.detail") : lastCoreError;
        context.showError(Strings.get("mcilm.title"), reason);
    }

    private void stopProcess() {
        MciLmProcess current = process;
        if (current == null || !current.isRunning()) {
            phase = Phase.READY;
            refresh();
            return;
        }
        statusProperty.set(Strings.get("mcilm.status.stopping"));
        progressProperty.set(-1);
        current.stop();
    }

    /// Downloads the latest core and refreshes the page when done.
    private void downloadCore() {
        busy = true;
        phase = Phase.CONNECTING;
        statusProperty.set(Strings.get("mcilm.status.downloading"));
        progressProperty.set(-1);
        refresh();

        Thread thread = new Thread(() -> {
            String error = null;
            String version = "";
            try {
                MciLmApi.Release release = MciLmApi.fetchLatestCli();
                version = release.version();
                MciLmApi.Artifact artifact = release.cli(MciLmApi.currentPlatform(), MciLmApi.currentArch());
                if (artifact == null)
                    throw new IOException("No CLI build for "
                            + MciLmApi.currentPlatform() + "/" + MciLmApi.currentArch());

                byte[] bytes = MciLmApi.download(artifact.url());
                Path temporary = dataDirectory.resolve("MciLm-linkc.download");
                Files.write(temporary, bytes);
                MciLmProcess.installExecutable(temporary, executable);
                Files.deleteIfExists(temporary);
                executable.toFile().setExecutable(true, false);
            } catch (Throwable e) {
                error = String.valueOf(e.getMessage());
                context.logError("Failed to download MciLm-link", e);
            }

            String finalVersion = version;
            String finalError = error;
            Platform.runLater(() -> {
                busy = false;
                phase = Phase.READY;
                refresh();
                if (finalError == null) {
                    context.showToast(Strings.format("mcilm.download.success", finalVersion));
                } else {
                    context.showError(Strings.get("mcilm.title"),
                            Strings.format("mcilm.download.failed", finalError));
                }
            });
        }, "MciLm-link download");
        thread.setDaemon(true);
        thread.start();
    }

    /// Stops the client when the plugin is disabled or the launcher exits.
    void shutdown() {
        stopProcess();
    }

    // ------------------------------------------------------------------ helpers

    private static String value(List<PromptDialogPane.Builder.Question<?>> result, int index) {
        Object value = result.get(index).getValue();
        return value == null ? "" : String.valueOf(value);
    }

    private static LineButton largeRow(SVG icon, String title, String subtitle) {
        LineButton button = new LineButton();
        button.setLargeTitle(true);
        button.setLeading(icon, ICON_SIZE);
        button.setTitle(title);
        button.setSubtitle(subtitle);
        button.setTrailingIcon(SVG.ARROW_FORWARD, ICON_SIZE);
        return button;
    }

    private static TextFlow hintText(String text) {
        TextFlow flow = FXUtils.segmentToTextFlow(text, Controllers::onHyperlinkAction);
        flow.getStyleClass().add("terracotta-hint");
        flow.setLineSpacing(4);
        return flow;
    }

    private void copy(String text) {
        if (StringUtils.isBlank(text))
            return;
        context.copyToClipboard(text);
        context.showToast(Strings.get("mcilm.copied"));
    }

    private boolean signedIn() {
        return StringUtils.isNotBlank(loadToken()) || StringUtils.isNotBlank(loadNode());
    }

    private String currentPort() {
        return loadPort();
    }

    private String displayNode() {
        String node = loadNode();
        return StringUtils.isBlank(node) ? Strings.get("mcilm.node.auto") : node;
    }

    private Path tokenFile() {
        return dataDirectory.resolve("token.txt");
    }

    private Path nodeFile() {
        return dataDirectory.resolve("node.txt");
    }

    private Path portFile() {
        return dataDirectory.resolve("port.txt");
    }

    private Path roomMaxFile() {
        return dataDirectory.resolve("room-max.txt");
    }

    private String loadToken() {
        return readText(tokenFile());
    }

    private String loadNode() {
        return readText(nodeFile());
    }

    private String loadPort() {
        String port = readText(portFile());
        return StringUtils.isBlank(port) ? "25565" : port;
    }

    private String loadRoomMax() {
        String max = readText(roomMaxFile());
        return StringUtils.isBlank(max) ? "5" : max;
    }

    private static String readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void storeText(Path file, String text) {
        try {
            Files.createDirectories(dataDirectory);
            if (StringUtils.isBlank(text)) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, text.trim());
            }
        } catch (IOException e) {
            context.logError("Failed to persist " + file.getFileName(), e);
        }
    }

    /// Reads a string member out of a flat JSON object without a JSON dependency.
    private static String extract(String json, String name) {
        String needle = "\"" + name + "\"";
        int index = json.indexOf(needle);
        if (index < 0)
            return null;
        int colon = json.indexOf(':', index + needle.length());
        if (colon < 0)
            return null;
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0)
            return null;
        int end = json.indexOf('"', quote + 1);
        if (end < 0)
            return null;
        return json.substring(quote + 1, end);
    }
}
