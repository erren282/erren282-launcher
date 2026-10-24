package org.dsh.servermanager;

import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.terracotta.TerracottaManager;
import org.jackhuang.hmcl.terracotta.TerracottaMetadata;
import org.jackhuang.hmcl.terracotta.TerracottaState;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/// The server management page shown in the main window sidebar (game section),
/// styled with the launcher's own components.
public final class ServerManagerPage extends VBox implements DecoratorPage {

    private final ServerManagerService service;

    /// 供宿主读取的页面状态。本页不是导航栈上的一页（栈上是 PluginPageProxy），
    /// 这里只需满足接口契约；标题与返回按钮由汇聚页的标题栏决定。
    private final ReadOnlyObjectWrapper<DecoratorPage.State> pageState =
            new ReadOnlyObjectWrapper<>(DecoratorPage.State.fromTitle("服务端管理"));

    private ComponentList serverListComponent;
    private Label detailTitle;
    private Label statusLabel;
    private GridPane detailFields;
    private JFXTextField nameField;
    private Label typeVersionLabel;
    private JFXTextField portField;
    private JFXTextField motdField;
    private JFXTextField minMemField;
    private JFXTextField maxMemField;
    private JFXComboBox<String> javaCombo;
    private final Map<String, String> javaDisplayNames = new java.util.HashMap<>();
    private JFXCheckBox broadcastCheck;
    private JFXCheckBox terracottaCheck;
    private JFXCheckBox eulaCheck;
    private JFXCheckBox onlineModeCheck;
    private JFXTextField maxPlayersField;
    private JFXComboBox<String> difficultyBox;
    private JFXComboBox<String> gamemodeBox;
    private JFXTextField viewDistanceField;
    private JFXTextField simulationDistanceField;
    private JFXCheckBox pvpCheck;
    private JFXCheckBox allowFlightCheck;
    private JFXCheckBox commandBlockCheck;
    private JFXTextField levelSeedField;
    private JFXTextField jvmArgsField;
    private LineButton startButton;
    private LineButton stopButton;
    private TextArea console;
    private JFXTextField commandField;
    /// Candidates of the Tab cycle in progress; empty when no cycle is active.
    private List<String> completionCandidates = List.of();
    /// Position within [completionCandidates]; -1 before the first Tab picks one.
    private int completionIndex = -1;
    /// The exact text the last completion produced, so the change listener can tell an
    /// application of a candidate apart from a real user edit.
    private String completionText = "";
    /// The untouched line and token range captured when the cycle started. Every Tab rewrites
    /// from these, which is what keeps repeated presses from accumulating text.
    private String completionBaseText = "";
    private CommandCompleter.Completion completionBase;
    /// Shows the current candidates under the input row.
    private Label completionHint;
    /// Console and the completion popup share one StackPane; the popup overlays the console.
    private javafx.scene.layout.StackPane consoleStack;
    /// The clickable candidate list (styled like the in-game command popup).
    private VBox completionList;
    private javafx.scene.control.ScrollPane completionScroll;

    // room code display (Terracotta)
    private VBox roomSection;
    private Label roomStatusLabel;
    private LineButton roomCodeButton;
    private LineButton roomRestoreButton;

    private ServerProfile current;
    private boolean updatingFields;

    /// Height of one candidate row, used to size the completion popup.
    private static final int ROW_HEIGHT = 24;

    /// Holds the server list, detail form and console; swapped out for the management sub-pages.
    private VBox mainView;
    private javafx.scene.control.Button scanJavaButton;
    /// “服务端内容管理”区块；未选择服务端时整块隐藏。
    private javafx.scene.Node manageSectionTitle;
    private ComponentList manageSectionList;
    private volatile boolean scanBusy;
    /// Shows either [mainView] or one of the sub-pages (players / plugins / store),
    /// animated so switching does not read as an abrupt text swap.
    private org.jackhuang.hmcl.ui.animation.TransitionPane contentArea;

    /// 返回“宿主页”（如「服务器」汇聚页）的动作；作为独立侧边栏入口时为 null。
    ///
    /// <p>只被 [#back] 使用：页面本身不再画返回按钮，回来路只有窗口左上角的原生箭头。</p>
    private final Runnable backToHost;

    public ServerManagerPage(ServerManagerService service) {
        this(service, null);
    }

    public ServerManagerPage(ServerManagerService service, Runnable backToHost) {
        this.backToHost = backToHost;
        this.service = service;
        setSpacing(10);
        setPadding(new Insets(10));

        contentArea = new org.jackhuang.hmcl.ui.animation.TransitionPane();
        mainView = new VBox(10);
        contentArea.setContent(mainView, org.jackhuang.hmcl.ui.animation.ContainerAnimations.NONE);
        getChildren().add(contentArea);

        buildMainView();

        service.addListener(new ServerManagerService.Listener() {
            @Override
            public void onProfilesChanged() {
                Platform.runLater(ServerManagerPage.this::reloadServerRows);
            }

            @Override
            public void onLog(ServerProfile profile, String line) {
                Platform.runLater(() -> appendConsole(profile, line));
            }

            @Override
            public void onStateChanged(ServerProfile profile) {
                Platform.runLater(() -> {
                    reloadServerRows();
                    if (isCurrent(profile)) {
                        refreshStatus();
                        refreshRoomUI();
                        if (!service.isRunning(profile))
                            releaseRoom();
                    }
                });
            }
        });

        // the Terracotta daemon state is global to the launcher; reflect it here
        TerracottaManager.stateProperty().addListener((observable, oldState, newState) ->
                Platform.runLater(this::refreshRoomUI));

        reloadServerRows();
        scanJavas();

        // First entry into this page shows its top, not wherever the shell scroll was left.
        // The reset must wait until the page is actually in the scene: in the constructor the
        // node has no parent, so no ancestor scroll pane can be found.
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null)
                ManageUi.scrollToTop(this);
        });
    }

    @Override
    public ReadOnlyObjectProperty<DecoratorPage.State> stateProperty() {
        return pageState.getReadOnlyProperty();
    }

    /// 窗口左上角返回箭头。语义与 [org.jackhuang.hmcl.ui.decorator.DecoratorController] 一致：
    /// {@code false} = 本页已处理，{@code true} = 请启动器弹栈。
    ///
    /// <ol>
    ///   <li>停在玩家管理 / 插件管理这类二级页面 → 先回到服务端主视图；</li>
    ///   <li>停在主视图且由汇聚页托管 → 回到「服务器」卡片选择界面；</li>
    ///   <li>作为独立侧边栏入口 → 交还启动器，正常退出本功能。</li>
    /// </ol>
    @Override
    public boolean back() {
        if (activeSubPage != null) {
            closeSubPage();
            return false;
        }
        if (backToHost != null) {
            backToHost.run();
            return false;
        }
        return true;
    }

    private void buildMainView() {
        // 页内不画返回按钮：返回统一由窗口左上角的原生箭头（和 ESC）承担，
        // 由 #back 决定是退出本模块还是先退回二级页面。
        mainView.getChildren().add(ComponentList.createComponentListTitle("服务端管理"));

        LineButton downloadButton = new LineButton();
        downloadButton.setTitle("新建 / 下载服务端");
        downloadButton.setSubtitle("前往下载页获取原版 / Paper 服务端，或导入现有服务端");
        downloadButton.setOnAction(e -> PluginMenuService.showDownloadTab(ServerManagerPlugin.downloadTab()));

        ComponentList topActions = new ComponentList();
        topActions.getContent().add(downloadButton);
        mainView.getChildren().add(topActions);

        mainView.getChildren().add(ComponentList.createComponentListTitle("服务端列表"));
        serverListComponent = new ComponentList();
        mainView.getChildren().add(serverListComponent);

        detailTitle = new Label("未选择服务端");
        detailTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        detailTitle.setPadding(new Insets(4, 0, 0, 0));
        statusLabel = new Label(" ");
        statusLabel.setStyle("-fx-text-fill: #666;");
        mainView.getChildren().addAll(detailTitle, statusLabel);

        buildDetailFields();
        mainView.getChildren().add(detailFields);
        mainView.getChildren().add(buildRoomSection());

        ComponentList actionList = new ComponentList();
        startButton = new LineButton();
        startButton.setTitle("一键开设并广播");
        startButton.setSubtitle("写入配置并启动服务端，同时向局域网广播并创建联机房间");
        startButton.setOnAction(e -> startCurrent());

        stopButton = new LineButton();
        stopButton.setTitle("停止服务端");
        stopButton.setSubtitle("向服务端控制台发送 stop 命令，30 秒后强制结束");
        stopButton.setOnAction(e -> stopCurrent());

        LineButton saveButton = new LineButton();
        saveButton.setTitle("保存设置");
        saveButton.setSubtitle("保存端口、MOTD、玩法选项与 Java 等修改");
        saveButton.setOnAction(e -> saveCurrent());

        LineButton openDirButton = new LineButton();
        openDirButton.setTitle("打开目录");
        openDirButton.setSubtitle("在资源管理器中打开该服务端的目录");
        openDirButton.setOnAction(e -> openCurrentDirectory());

        LineButton deleteButton = new LineButton();
        deleteButton.setTitle("删除");
        deleteButton.setSubtitle("删除实例记录；插件创建的实例目录会一并删除");
        deleteButton.setOnAction(e -> deleteCurrent());

        actionList.getContent().setAll(startButton, stopButton, saveButton, openDirButton, deleteButton);
        mainView.getChildren().add(actionList);

        // sub-pages: each manages one aspect of the selected server
        LineButton playersButton = new LineButton();
        playersButton.setTitle("玩家管理");
        playersButton.setSubtitle("在线玩家、OP 权限、白名单与封禁（含封禁时长与解禁）");
        playersButton.setOnAction(e -> showSubPage(SubPage.PLAYERS));

        LineButton pluginsButton = new LineButton();
        pluginsButton.setTitle("插件管理");
        pluginsButton.setSubtitle("查看当前服务端的插件，启停或删除，并从插件商店下载");
        pluginsButton.setOnAction(e -> showSubPage(SubPage.PLUGINS));

        ComponentList manageList = new ComponentList();
        manageList.getContent().setAll(playersButton, pluginsButton);
        // 未选择服务端时隐藏整块：没有服务端就没有“玩家/插件”可管理
        manageSectionTitle = ComponentList.createComponentListTitle("服务端内容管理");
        manageSectionList = manageList;
        mainView.getChildren().add(manageSectionTitle);
        mainView.getChildren().add(manageList);

        mainView.getChildren().add(ComponentList.createComponentListTitle("控制台"));
        console = new TextArea();
        console.setEditable(false);
        console.setWrapText(true);
        console.setStyle("-fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
        console.setPrefHeight(200);
        // 控制台与补全面板同层：面板悬浮在控制台底部，永远不参与布局（managed=false），
        // 页面高度因此与补全面板的显隐完全无关——这是“弹补全时整页上提”的根治方案。
        consoleStack = new javafx.scene.layout.StackPane(console);
        mainView.getChildren().add(consoleStack);

        HBox commandRow = new HBox(8);
        commandRow.setAlignment(Pos.CENTER_LEFT);
        commandField = new JFXTextField();
        commandField.setPromptText("向服务端控制台发送命令，例如 list / say 大家好（Tab 补全）");
        HBox.setHgrow(commandField, Priority.ALWAYS);
        javafx.scene.control.Button sendButton = FXUtils.newRaisedButton("发送");
        sendButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        sendButton.setOnAction(e -> sendCommand());
        commandField.setOnAction(e -> sendCommand());
        installTabCompletion(commandField);
        commandRow.getChildren().addAll(commandField, sendButton);
        mainView.getChildren().add(commandRow);

        // the clickable suggestion list, styled after the in-game command popup.
        // The colours come from the launcher's own theme variables (`-monet-*`), so the list
        // follows the launcher's light/dark setting instead of being hard-coded.
        // Only the ScrollPane is shown and hidden: also hiding this inner box would leave the
        // popup frame visible but permanently empty, since the rows would never be painted.
        completionList = new VBox(0);
        completionList.setStyle("-fx-background-color: -monet-surface-container;");
        completionScroll = new javafx.scene.control.ScrollPane(completionList);
        completionScroll.setFitToWidth(true);
        // Tall enough to show a useful number of rows without covering the console; the list
        // scrolls when there are more candidates (a bare Tab on an empty field lists ~100).
        completionScroll.setPrefHeight(ROW_HEIGHT * 6);
        completionScroll.setMaxHeight(ROW_HEIGHT * 10);
        completionScroll.setStyle("-fx-background-color: -monet-surface-container;"
                + " -fx-border-color: -monet-primary; -fx-border-width: 1;");
        completionScroll.setVisible(false);
        completionScroll.setManaged(false);
        FXUtils.smoothScrolling(completionScroll);
        consoleStack.getChildren().add(completionScroll);
        javafx.scene.layout.StackPane.setAlignment(completionScroll, javafx.geometry.Pos.BOTTOM_LEFT);

        completionHint = new Label(" ");
        completionHint.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        // 单行 + 省略号：换行会让这一行的高度随命令长度变化，页面又会跟着跳
        completionHint.setWrapText(false);
        completionHint.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
        mainView.getChildren().add(completionHint);

        setDetailVisible(false);
    }

    // ------------------------------------------------------- sub-pages ---

    /// Which management sub-page is displayed.
    private enum SubPage {
        PLAYERS, PLUGINS
    }

    /// The sub-page currently displayed, if any.
    ///
    /// <p>Kept so it can be told to stop following the console when it is replaced. The player
    /// page registers a log listener to refresh itself, and a discarded page that kept that
    /// listener would go on rebuilding an invisible UI for every server line.</p>
    private Node activeSubPage;

    /// Replaces the content area with a sub-page for the selected server.
    private void showSubPage(SubPage page) {
        if (current == null) {
            ServerManagerPlugin.error("打开管理页", "请先在左侧列表中选择一个服务端。");
            return;
        }
        disposeSubPage();
        javafx.scene.Node node = switch (page) {
            case PLAYERS -> new PlayerManagerPage(service, current,
                    () -> service.getProcess(current));
            case PLUGINS -> new PluginManagerPage(service, current);
        };
        activeSubPage = node;
        contentArea.setContent(node, org.jackhuang.hmcl.ui.animation.ContainerAnimations.FORWARD);
        // a fresh sub-page starts at its own top, not at the depth the outer page was scrolled to;
        // onShown must run *after* setAll so the reset can reach the shell scroll pane
        if (node instanceof PlayerManagerPage players)
            players.onShown();
        else if (node instanceof PluginManagerPage plugins)
            plugins.onShown();
        else
            ManageUi.scrollToTop(node);
    }

    /// Returns to the main server view.
    ///
    /// <p>Re-selects the profile so every field is re-read: a sub-page may have changed the
    /// whitelist flag (or anything else), and leaving a stale checkbox behind would let a later
    /// save on this page silently undo it.</p>
    private void closeSubPage() {
        disposeSubPage();
        contentArea.setContent(mainView, org.jackhuang.hmcl.ui.animation.ContainerAnimations.BACKWARD);
        if (current != null)
            selectProfile(current);
        reloadServerRows();
        refreshStatus();
        // back to the main view also means back to its top
        ManageUi.scrollToTop(this);
    }

    /// Releases the active sub-page's subscriptions.
    ///
    /// <p>Called both when a sub-page is replaced and when one is closed, so the console
    /// listener is never left behind.</p>
    private void disposeSubPage() {
        Node previous = activeSubPage;
        activeSubPage = null;
        if (previous instanceof PlayerManagerPage players)
            players.dispose();
    }

    private void buildDetailFields() {
        detailFields = new GridPane();
        detailFields.setHgap(10);
        detailFields.setVgap(8);
        detailFields.setPadding(new Insets(4, 0, 4, 0));
        // keep the label column readable on narrow windows
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        detailFields.getColumnConstraints().addAll(labelColumn, fieldColumn);

        int row = 0;
        detailFields.add(new Label("类型 / 版本"), 0, row);
        typeVersionLabel = new Label("-");
        detailFields.add(typeVersionLabel, 1, row++);

        detailFields.add(new Label("名称"), 0, row);
        nameField = new JFXTextField();
        detailFields.add(nameField, 1, row++);

        detailFields.add(new Label("端口"), 0, row);
        HBox portRow = new HBox(8);
        portRow.setAlignment(Pos.CENTER_LEFT);
        portField = new JFXTextField();
        portField.setPrefWidth(90);
        portRow.getChildren().addAll(portField, new Label("MOTD"));
        motdField = new JFXTextField();
        HBox.setHgrow(motdField, Priority.ALWAYS);
        portRow.getChildren().add(motdField);
        detailFields.add(portRow, 1, row++);

        detailFields.add(new Label("内存 (MB)"), 0, row);
        HBox memoryRow = new HBox(8);
        memoryRow.setAlignment(Pos.CENTER_LEFT);
        minMemField = new JFXTextField();
        maxMemField = new JFXTextField();
        minMemField.setPrefWidth(70);
        maxMemField.setPrefWidth(70);
        memoryRow.getChildren().addAll(new Label("最小"), minMemField, new Label("最大"), maxMemField);
        detailFields.add(memoryRow, 1, row++);

        detailFields.add(new Label("Java 路径"), 0, row);
        HBox javaRow = new HBox(8);
        javaRow.setAlignment(Pos.CENTER_LEFT);
        // items are raw paths so selecting one always yields an exact editor value;
        // the popup prettifies them with the version from the release file
        javaCombo = new JFXComboBox<>();
        javaCombo.setEditable(true);
        javaCombo.setPromptText("留空使用自带 Java；点“扫描”自动查找本机 Java");
        HBox.setHgrow(javaCombo, Priority.ALWAYS);
        javaCombo.setMaxWidth(Double.MAX_VALUE);
        javaCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : javaDisplayNames.getOrDefault(item, item));
            }
        });
        javafx.scene.control.Button chooseJavaButton = FXUtils.newRaisedButton("选择");
        chooseJavaButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        chooseJavaButton.setOnAction(e -> chooseJava());
        scanJavaButton = FXUtils.newRaisedButton("扫描");
        scanJavaButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        scanJavaButton.setOnAction(e -> scanJavas());
        javafx.scene.control.Button downloadJavaButton = FXUtils.newRaisedButton("下载 Java");
        downloadJavaButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        downloadJavaButton.setOnAction(e -> openJavaDownload());
        javaRow.getChildren().addAll(javaCombo, chooseJavaButton, scanJavaButton, downloadJavaButton);
        detailFields.add(javaRow, 1, row++);

        // ---- gameplay options: two columns per row ----
        onlineModeCheck = new JFXCheckBox("正版验证");
        detailFields.add(onlineModeCheck, 0, row);
        HBox maxPlayersRow = new HBox(8);
        maxPlayersRow.setAlignment(Pos.CENTER_LEFT);
        maxPlayersField = new JFXTextField();
        maxPlayersField.setPrefWidth(70);
        maxPlayersRow.getChildren().addAll(new Label("最大玩家数"), maxPlayersField);
        detailFields.add(maxPlayersRow, 1, row++);

        detailFields.add(new Label("玩法"), 0, row);
        HBox gameplayRow = new HBox(8);
        gameplayRow.setAlignment(Pos.CENTER_LEFT);
        difficultyBox = new JFXComboBox<>();
        difficultyBox.getItems().setAll("peaceful", "easy", "normal", "hard");
        difficultyBox.setPrefWidth(100);
        gamemodeBox = new JFXComboBox<>();
        gamemodeBox.getItems().setAll("survival", "creative", "adventure");
        gamemodeBox.setPrefWidth(100);
        gameplayRow.getChildren().addAll(new Label("难度"), difficultyBox, new Label("模式"), gamemodeBox);
        detailFields.add(gameplayRow, 1, row++);

        detailFields.add(new Label("距离"), 0, row);
        HBox distanceRow = new HBox(8);
        distanceRow.setAlignment(Pos.CENTER_LEFT);
        viewDistanceField = new JFXTextField();
        viewDistanceField.setPrefWidth(60);
        simulationDistanceField = new JFXTextField();
        simulationDistanceField.setPrefWidth(60);
        distanceRow.getChildren().addAll(new Label("视距"), viewDistanceField,
                new Label("模拟"), simulationDistanceField);
        detailFields.add(distanceRow, 1, row++);

        pvpCheck = new JFXCheckBox("PvP");
        allowFlightCheck = new JFXCheckBox("允许飞行");
        commandBlockCheck = new JFXCheckBox("命令方块");
        // The white-list switch lives in 玩家管理 and nowhere else. Having a second one here
        // meant two places to toggle the same flag, which could disagree with each other
        // and with server.properties; the player page reads and writes the live value.
        HBox gameplayFlags = new HBox(20);
        gameplayFlags.setAlignment(Pos.CENTER_LEFT);
        gameplayFlags.getChildren().addAll(pvpCheck, allowFlightCheck);
        GridPane.setColumnSpan(gameplayFlags, 2);
        detailFields.add(gameplayFlags, 0, row++);
        HBox serverFlags = new HBox(20);
        serverFlags.setAlignment(Pos.CENTER_LEFT);
        serverFlags.getChildren().addAll(commandBlockCheck);
        GridPane.setColumnSpan(serverFlags, 2);
        detailFields.add(serverFlags, 0, row++);

        detailFields.add(new Label("世界种子"), 0, row);
        levelSeedField = new JFXTextField();
        levelSeedField.setPromptText("留空保持不变；仅对新世界生效");
        detailFields.add(levelSeedField, 1, row++);

        detailFields.add(new Label("JVM 参数"), 0, row);
        jvmArgsField = new JFXTextField();
        jvmArgsField.setPromptText("附加 JVM 参数，空格分隔，例如 -Dlog4j.skipJansi=true");
        detailFields.add(jvmArgsField, 1, row++);

        broadcastCheck = new JFXCheckBox("向局域网广播（并在代理端口代答心跳，服务端重启不掉线）");
        detailFields.add(broadcastCheck, 0, row++);
        terracottaCheck = new JFXCheckBox("创建陶瓦联机房间（好友跨网络用房间码加入）");
        detailFields.add(terracottaCheck, 0, row++);
        eulaCheck = new JFXCheckBox("已阅读并同意 Minecraft EULA（首次启动前需勾选）");
        detailFields.add(eulaCheck, 0, row++);
    }

    /// The "联机房间" section shown while the selected server is running.
    private VBox buildRoomSection() {
        roomSection = new VBox(6);
        roomSection.setPadding(new Insets(4, 0, 0, 0));

        roomStatusLabel = new Label(" ");
        roomStatusLabel.setStyle("-fx-text-fill: #666;");

        roomCodeButton = new LineButton();
        roomCodeButton.setLargeTitle(true);
        roomCodeButton.setTitle("-");
        roomCodeButton.setSubtitle("点击复制房间码，好友在「多人联机 → 加入房间」输入即可跨网络加入");
        roomCodeButton.setOnAction(e -> {
            String code = roomCodeButton.getTitle();
            if (code != null && code.matches("[A-Z]/[A-Z0-9-]+"))
                FXUtils.copyText(code);
        });
        roomCodeButton.setVisible(false);
        roomCodeButton.setManaged(false);

        roomRestoreButton = new LineButton();
        roomRestoreButton.setTitle("创建 / 恢复房间");
        roomRestoreButton.setSubtitle("服务端正在运行但房间尚未建立，点击重新扫描并创建房间码");
        roomRestoreButton.setOnAction(e -> {
            if (current != null)
                startRoomAfterStartup(current);
        });
        roomRestoreButton.setVisible(false);
        roomRestoreButton.setManaged(false);

        roomSection.getChildren().addAll(roomStatusLabel, roomCodeButton, roomRestoreButton);
        roomSection.setVisible(false);
        roomSection.setManaged(false);
        return roomSection;
    }

    // ------------------------------------------------------------- rows ---

    private String buildRowSubtitle(ServerProfile profile) {
        StringBuilder builder = new StringBuilder(profile.getType().getDisplayName());
        if (!profile.getMcVersion().isEmpty())
            builder.append(' ').append(profile.getMcVersion());
        builder.append("  ·  端口 ").append(profile.getPort());
        if (service.isRunning(profile)) {
            int proxyPort = service.getProxyPort(profile);
            builder.append("  ·  ● 运行中");
            if (proxyPort > 0)
                builder.append("（广播代理 ").append(proxyPort).append('）');
        } else {
            builder.append("  ·  已停止");
        }
        return builder.toString();
    }

    private void reloadServerRows() {
        serverListComponent.getContent().clear();
        for (ServerProfile profile : service.getProfiles()) {
            LineButton row = new LineButton();
            row.setTitle(profile.getName());
            row.setSubtitle(buildRowSubtitle(profile));
            row.setOnAction(e -> selectProfile(profile));
            serverListComponent.getContent().add(row);
        }
        if (current != null && service.getProfiles().stream().noneMatch(p -> p.getId().equals(current.getId()))) {
            current = null;
            selectProfile(null);
        }
        if (current != null)
            refreshStatus();
        // Open on the server that is actually running: otherwise the detail area stays blank
        // and it looks like there is nothing to start.
        if (current == null) {
            for (ServerProfile profile : service.getProfiles()) {
                if (service.isRunning(profile)) {
                    selectProfile(profile);
                    break;
                }
            }
        }
    }

    private void selectProfile(ServerProfile profile) {
        current = profile;
        updatingFields = true;
        try {
            boolean has = profile != null;
            setDetailVisible(has);
            if (!has) {
                detailTitle.setText("未选择服务端");
                statusLabel.setText(" ");
                console.clear();
                refreshRoomUI();
                reloadServerRows();
                return;
            }
            // 换一台服务端时，详情区短暂淡入，替代生硬的文字替换
            playDetailFade();

            detailTitle.setText(profile.getName());
            typeVersionLabel.setText(profile.getType().getDisplayName()
                    + (profile.getMcVersion().isEmpty() ? "" : "  ·  " + profile.getMcVersion()));
            nameField.setText(profile.getName());
            portField.setText(String.valueOf(profile.getPort()));
            motdField.setText(profile.getMotd());
            minMemField.setText(String.valueOf(profile.getMinMemory()));
            maxMemField.setText(String.valueOf(profile.getMaxMemory()));
            javaCombo.getEditor().setText(profile.getJavaPath());
            onlineModeCheck.setSelected(profile.isOnlineMode());
            maxPlayersField.setText(String.valueOf(profile.getMaxPlayers()));
            difficultyBox.getSelectionModel().select(profile.getDifficulty());
            gamemodeBox.getSelectionModel().select(profile.getGamemode());
            viewDistanceField.setText(String.valueOf(profile.getViewDistance()));
            simulationDistanceField.setText(String.valueOf(profile.getSimulationDistance()));
            pvpCheck.setSelected(profile.isPvp());
            allowFlightCheck.setSelected(profile.isAllowFlight());
            commandBlockCheck.setSelected(profile.isCommandBlock());
            levelSeedField.setText(profile.getLevelSeed());
            jvmArgsField.setText(profile.getJvmArgs());
            broadcastCheck.setSelected(profile.isBroadcast());
            terracottaCheck.setSelected(profile.isTerracottaRoom());
            eulaCheck.setSelected(profile.isEulaAccepted());

            console.clear();
            ServerProcess process = service.getProcess(profile);
            if (process != null)
                for (String line : process.getHistory())
                    console.appendText(line + "\n");
            refreshStatus();
            refreshRoomUI();
            reloadServerRows();
        } finally {
            updatingFields = false;
        }
    }

    private void setDetailVisible(boolean visible) {
        detailFields.setVisible(visible);
        detailFields.setManaged(visible);
        startButton.setVisible(visible);
        startButton.setManaged(visible);
        stopButton.setVisible(visible);
        stopButton.setManaged(visible);
        if (manageSectionTitle != null) {
            manageSectionTitle.setVisible(visible);
            manageSectionTitle.setManaged(visible);
        }
        if (manageSectionList != null) {
            manageSectionList.setVisible(visible);
            manageSectionList.setManaged(visible);
        }
    }

    private void refreshStatus() {
        if (current == null)
            return;
        ServerProcess process = service.getProcess(current);
        boolean external = process != null && process.isExternal();
        stopButton.setSubtitle(external
                ? "外部接管的服务端只能强制结束（可能丢失最近的改动）"
                : "向服务端控制台发送 stop 命令，30 秒后强制结束");
        boolean running = service.isRunning(current);
        if (running) {
            int proxyPort = service.getProxyPort(current);
            statusLabel.setText("● 运行中" + (proxyPort > 0
                    ? "，局域网广播中（端口 " + current.getPort() + " → 广播代理 " + proxyPort + "）"
                    : "，广播未启用"));
            statusLabel.setStyle("-fx-text-fill: #1a9b4b;");
        } else {
            statusLabel.setText("已停止");
            statusLabel.setStyle("-fx-text-fill: #666;");
        }
    }

    private boolean isCurrent(ServerProfile profile) {
        return current != null && profile != null && current.getId().equals(profile.getId());
    }

    /// Re-reads the profile's Java path into the editor after the fix dialog changed it.
    ///
    /// <p>[saveCurrent] writes the editor's text back into the profile, so a stale editor would
    /// silently undo the switch on the next start or save.</p>
    private void refreshJavaField(ServerProfile profile) {
        if (!isCurrent(profile) || updatingFields)
            return;
        updatingFields = true;
        try {
            javaCombo.getEditor().setText(profile.getJavaPath());
        } finally {
            updatingFields = false;
        }
        appendConsoleLine("[server-manager] Java 已更新为：" + profile.getJavaPath());
    }

    /// Opens the launcher's Java management page, where a runtime can be downloaded;
    /// run the scan afterwards to pick it up.
    private void openJavaDownload() {
        org.jackhuang.hmcl.ui.Controllers.getSettingsPage().showJavaManagement();
        org.jackhuang.hmcl.ui.Controllers.navigate(org.jackhuang.hmcl.ui.Controllers.getSettingsPage());
    }

    /// Scans the computer for Java installations in the background and fills the combo.
    /// Uses the full disk scan (every drive, Program Files, PATH), with live progress on the
    /// button, so it is a real scan rather than a glance at a few known folders.
    private void scanJavas() {
        if (scanBusy)
            return;
        scanBusy = true;
        scanJavaButton.setDisable(true);
        appendConsoleLine("[server-manager] 正在扫描本机 Java（所有盘符 + Program Files + PATH，约几秒到几十秒）…");
        new Thread(() -> {
            List<JavaScanner.JavaInfo> javas = JavaScanner.scanDeep(service.getDataDirectory(), checked ->
                    Platform.runLater(() -> {
                        if (scanJavaButton != null)
                            scanJavaButton.setText("扫描中… " + checked);
                    }));
            Platform.runLater(() -> {
                scanBusy = false;
                scanJavaButton.setDisable(false);
                scanJavaButton.setText("扫描");
                javaDisplayNames.clear();
                List<String> paths = new ArrayList<>();
                for (JavaScanner.JavaInfo info : javas) {
                    javaDisplayNames.put(info.getPath(), info.toString());
                    paths.add(info.getPath());
                }
                javaCombo.getItems().setAll(paths);
                if (!javas.isEmpty() && (javaCombo.getEditor().getText() == null || javaCombo.getEditor().getText().isBlank()))
                    javaCombo.getEditor().setText(javas.get(0).getPath());
                appendConsoleLine("[server-manager] 扫描完成：共找到 " + javas.size()
                        + " 个 Java，已列入下拉框" + (javas.isEmpty() ? "" : "，第一个是 " + javas.get(0).getPath()));
            });
        }, "server-manager-java-scan").start();
    }

    // ------------------------------------------------------ room code ---

    /// Reflects the shared Terracotta daemon state into the room section of the
    /// selected server.
    private void refreshRoomUI() {
        if (current == null || !service.isRunning(current) || !current.isTerracottaRoom()) {
            roomSection.setVisible(false);
            roomSection.setManaged(false);
            return;
        }
        roomSection.setVisible(true);
        roomSection.setManaged(true);
        roomRestoreButton.setVisible(false);
        roomRestoreButton.setManaged(false);

        TerracottaState state = TerracottaManager.stateProperty().get();
        if (state instanceof TerracottaState.HostOK hostOK) {
            roomStatusLabel.setText("房间已创建，把房间码发给好友：");
            roomStatusLabel.setStyle("-fx-text-fill: #1a9b4b;");
            String code = hostOK.getCode();
            if (!code.equals(roomCodeButton.getTitle())) {
                roomCodeButton.setTitle(code);
                appendConsoleLine("[server-manager] 房间码：" + code);
            }
            roomCodeButton.setVisible(true);
            roomCodeButton.setManaged(true);
        } else if (state instanceof TerracottaState.HostScanning
                || state instanceof TerracottaState.HostStarting) {
            roomStatusLabel.setText("正在创建联机房间…（首次扫描最多需要十几秒）");
            roomStatusLabel.setStyle("-fx-text-fill: #666;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
        } else if (state instanceof TerracottaState.Exception
                || state instanceof TerracottaState.Fatal) {
            roomStatusLabel.setText("房间创建失败，请到「多人联机」页面检查联机核心与网络后重试。");
            roomStatusLabel.setStyle("-fx-text-fill: #c62828;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
        } else if (state instanceof TerracottaState.Uninitialized) {
            roomStatusLabel.setText("联机核心未安装：请先打开「多人联机」页面完成初始化下载。");
            roomStatusLabel.setStyle("-fx-text-fill: #c62828;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
        } else if (state instanceof TerracottaState.Waiting || state instanceof TerracottaState.Unknown) {
            roomStatusLabel.setText("服务端正在运行，但尚未建立联机房间：");
            roomStatusLabel.setStyle("-fx-text-fill: #666;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
            roomRestoreButton.setVisible(true);
            roomRestoreButton.setManaged(true);
        } else {
            roomStatusLabel.setText("等待房间状态…");
            roomStatusLabel.setStyle("-fx-text-fill: #666;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
        }
    }

    /// Called after a successful start: drives the shared Terracotta daemon into
    /// host-scanning mode so it discovers the bridge and creates a room code.
    /// Opens a file chooser to pick the java executable for the selected instance.
    private void chooseJava() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择 Java 可执行文件（java.exe）");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                "Java", windows ? new String[]{"java.exe", "*.exe"} : new String[]{"java", "*"}));
        java.io.File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null)
            javaCombo.getEditor().setText(file.getAbsolutePath());
    }

    /// Called after a successful start: drives the shared Terracotta daemon into
    /// host-scanning mode so it discovers the bridge and creates a room code.
    /// The daemon may still be booting when the server starts, so wait for it.
    private void startRoomAfterStartup(ServerProfile profile) {
        if (profile == null || !profile.isTerracottaRoom() || !service.isRunning(profile))
            return;
        if (TerracottaMetadata.PROVIDER == null) {
            appendConsoleLine("[server-manager] 当前平台不支持陶瓦联机，已跳过房间创建");
            return;
        }
        new Thread(() -> {
            boolean ready = false;
            for (int i = 0; i < 10 && service.isRunning(profile); i++) {
                TerracottaState state = TerracottaManager.stateProperty().get();
                if (state instanceof TerracottaState.Waiting
                        || state instanceof TerracottaState.HostScanning
                        || state instanceof TerracottaState.HostStarting
                        || state instanceof TerracottaState.HostOK) {
                    ready = true;
                    break;
                }
                if (state instanceof TerracottaState.Uninitialized) {
                    Platform.runLater(() -> appendConsoleLine(
                            "[server-manager] 联机核心未安装：请先打开「多人联机」页面完成初始化下载"));
                    return;
                }
                if (state instanceof TerracottaState.Fatal) {
                    Platform.runLater(() -> appendConsoleLine(
                            "[server-manager] 联机核心启动失败：请到「多人联机」页面查看详情"));
                    return;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!service.isRunning(profile))
                return;
            if (!ready) {
                Platform.runLater(() -> appendConsoleLine(
                        "[server-manager] 联机核心长时间未就绪，未能创建房间（可停止后重新开设）"));
                return;
            }
            Platform.runLater(() -> {
                if (!service.isRunning(profile))
                    return;
                TerracottaManager.switchDaemon(true);
                TerracottaState.HostScanning scanning = TerracottaManager.setScanning();
                if (scanning == null)
                    appendConsoleLine("[server-manager] 未能进入房间创建流程（可停止后重新开设）");
                else
                    appendConsoleLine("[server-manager] 正在创建联机房间…");
                refreshRoomUI();
            });
        }, "server-manager-room").start();
    }

    /// Releases the shared daemon's host flow when our server stopped.
    private void releaseRoom() {
        boolean anyRunningWithRoom = service.getProfiles().stream()
                .anyMatch(p -> service.isRunning(p) && p.isTerracottaRoom());
        if (!anyRunningWithRoom) {
            TerracottaState state = TerracottaManager.stateProperty().get();
            if (state instanceof TerracottaState.HostOK
                    || state instanceof TerracottaState.HostScanning
                    || state instanceof TerracottaState.HostStarting)
                TerracottaManager.setWaiting();
        }
        refreshRoomUI();
    }

    private void appendConsoleLine(String line) {
        console.appendText(line + "\n");
        console.setScrollTop(Double.MAX_VALUE);
    }

    // ------------------------------------------------------------ actions ---

    private void startCurrent() {
        if (!saveCurrent())
            return;
        ServerProfile profile = current;
        if (profile == null) {
            ServerManagerPlugin.error("启动服务端", "请先在左侧列表中选择一个服务端。");
            return;
        }
        // A server that is already up must not end in a dead end: offer to restart it instead
        // of failing with "服务端已在运行", which reads as "cannot start".
        if (service.isRunning(profile)) {
            ServerManagerPlugin.log("服务端 " + profile.getName() + " 已在运行（端口 " + profile.getPort()
                    + "），等待用户确认是否重启");
            ServerManagerPlugin.confirm("服务端已在运行",
                    "服务端「" + profile.getName() + "」正在运行（端口 " + profile.getPort() + "）。\n\n"
                            + "点击“确定”将先停止它再重新启动；如果只是想连上它，可直接点“取消”并使用当前运行中的服务端。",
                    () -> restart(profile));
            return;
        }
        start(profile);
    }

    private void start(ServerProfile profile) {
        ServerManagerPlugin.log("启动服务端 " + profile.getName() + "（端口 " + profile.getPort()
                + "，目录 " + profile.getDirectory() + "）");
        checkJavaThen(profile, () -> runAsync("启动服务端失败", () -> {
            service.start(profile);
            ServerManagerPlugin.log("服务端 " + profile.getName() + " 已启动（端口 " + profile.getPort() + "）");
            Platform.runLater(() -> startRoomAfterStartup(profile));
        }));
    }

    /// Checks the profile's Java against the server it will run, on a background thread, and
    /// either warns the user or proceeds with `action`.
    ///
    /// <p>A blocking mismatch opens the fix dialog instead of starting: the server would only
    /// die a few seconds later with an error the user cannot act on. A warning mismatch offers
    /// the same dialog but lets the user continue, because those servers usually do boot.</p>
    private void checkJavaThen(ServerProfile profile, Runnable action) {
        Thread worker = new Thread(() -> {
            JavaCompatibility.Verdict verdict = JavaCompatibility.check(profile);
            Platform.runLater(() -> {
                switch (verdict.getSeverity()) {
                    case BLOCKING -> {
                        ServerManagerPlugin.log("Java 不匹配，已阻止启动：" + verdict.getHeadline()
                                + "（" + verdict.getActualVersion() + "，路径 " + verdict.getJavaPath() + "）");
                        appendConsoleLine("[server-manager] " + verdict.getHeadline() + "：" + verdict.getDetail());
                        JavaFixDialog.show(profile, service, ServerManagerPlugin.context(), verdict,
                                this::start, null, () -> refreshJavaField(profile));
                    }
                    case WARNING -> {
                        ServerManagerPlugin.log("Java 版本偏高（仅提示）：" + verdict.getActualVersion());
                        appendConsoleLine("[server-manager] " + verdict.getHeadline());
                        JavaFixDialog.show(profile, service, ServerManagerPlugin.context(), verdict,
                                this::start, action, () -> refreshJavaField(profile));
                    }
                    case OK -> action.run();
                }
            });
        }, "server-manager-java-check");
        worker.setDaemon(true);
        worker.start();
    }

    /// Restarts a running server. The Java check runs before the server is stopped, so a
    /// mismatch leaves the running server untouched instead of taking it down for nothing.
    private void restart(ServerProfile profile) {
        ServerManagerPlugin.log("重新启动服务端 " + profile.getName());
        checkJavaThen(profile, () -> runAsync("重新启动服务端失败", () -> {
            service.stop(profile);
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && service.isRunning(profile))
                Thread.sleep(500);
            if (service.isRunning(profile))
                throw new IOException("停止服务端超时，请稍后重试或结束其进程");
            service.start(profile);
            ServerManagerPlugin.log("服务端 " + profile.getName() + " 已重新启动（端口 " + profile.getPort() + "）");
            Platform.runLater(() -> startRoomAfterStartup(profile));
        }));
    }

    private void stopCurrent() {
        if (current != null)
            ServerManagerPlugin.log("停止服务端 " + current.getName());
        ServerProfile profile = current;
        if (profile == null)
            return;
        runAsync("停止服务端失败", () -> service.stop(profile));
    }

    private void sendCommand() {
        String command = commandField.getText();
        ServerProfile profile = current;
        if (command == null || command.isBlank() || profile == null)
            return;
        commandField.clear();
        resetCompletion();
        runAsync("发送命令失败", () -> service.sendCommand(profile, command.trim()));
    }

    // ------------------------------------------------------ tab completion ---

    /// Wires Tab handling into the command field: Tab completes, Shift+Tab steps back through
    /// the candidates, and any other edit clears the cycle so the next Tab starts fresh.
    private void installTabCompletion(JFXTextField field) {
        field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != javafx.scene.input.KeyCode.TAB)
                return;
            // Tab must not move focus: inside the launcher it would jump to the next control.
            event.consume();
            cycleCompletion(event.isShiftDown() ? -1 : 1);
        });
        field.textProperty().addListener((observable, oldText, newText) -> {
            // Applying a candidate rewrites the text itself; only a real user edit ends the cycle.
            if (!newText.equals(completionText))
                resetCompletion();
        });
    }

    /// Handles one Tab press.
    ///
    /// <p>A single candidate is applied outright — and when that candidate is the command name a
    /// space is appended so the *next* Tab moves on to the first argument. Several candidates are
    /// cycled through instead, the list stays open for further Tabs, and Shift+Tab steps back.</p>
    private void cycleCompletion(int direction) {
        if (completionCandidates.isEmpty()) {
            String text = commandField.getText() == null ? "" : commandField.getText();
            CommandCompleter.Completion completion = CommandCompleter.complete(
                    text, commandField.getCaretPosition(), current, service.getDataDirectory());
            if (completion == null || completion.getCandidates().isEmpty()) {
                hideCompletionList();
                showCompletionHint("没有匹配的命令或参数", false);
                return;
            }
            completionCandidates = completion.getCandidates();
            completionIndex = -1;
            // Keep the untouched line as the base so every step rebuilds from the same text.
            completionBaseText = text;
            completionBase = completion;
        }

        boolean single = completionCandidates.size() == 1;
        completionIndex = Math.floorMod(completionIndex + direction, completionCandidates.size());
        applyCandidate(completionCandidates.get(completionIndex));

        if (single) {
            // Nothing left to choose: close it so the next Tab completes the following token.
            resetCompletion();
            showCompletionHint("已补全：" + commandField.getText().trim(), true);
            return;
        }

        showCompletionList(completionCandidates,
                completionCandidates.get(completionIndex));
        showCompletionHint("(" + (completionIndex + 1) + "/" + completionCandidates.size() + ")"
                + "  继续按 Tab 切换，Shift+Tab 返回，或直接点击下面的候选项", true);
    }

    /// Writes `candidate` into the field, replacing the token the cycle is filling in.
    private void applyCandidate(String candidate) {
        // A command name is finished off with a space so the next Tab moves to its first
        // argument (as the in-game chat does). This is safe while cycling because every step
        // rebuilds from the untouched base line, so spaces can never accumulate.
        boolean appendSpace = completionBase != null && completionBase.getTokenStart() == 0;
        CommandCompleter.Applied applied =
                CommandCompleter.apply(completionBaseText, completionBase, candidate, appendSpace);
        completionText = applied.text();
        completionFieldSet(applied.text(), applied.caret());
    }

    /// Fills the clickable candidate list. Each row behaves like the in-game command popup:
    /// clicking it completes the command, so no keyboard step is needed.
    private void showCompletionList(List<String> candidates, String selected) {
        if (completionList == null)
            return;
        completionList.getChildren().clear();
        selectedRow = null;
        for (String candidate : candidates) {
            boolean isSelected = candidate.equals(selected);
            Label row = new Label(candidate);
            row.setMaxWidth(Double.MAX_VALUE);
            row.setPadding(new Insets(4, 8, 4, 8));
            row.setStyle(selectedRowStyle(isSelected));
            // Clicking applies this candidate and closes the list, mirroring a mouse pick in game.
            row.setOnMouseClicked(event -> {
                if (completionBase != null) {
                    applyCandidate(candidate);
                    resetCompletion();
                    showCompletionHint("已补全：" + commandField.getText().trim(), true);
                }
            });
            row.setOnMouseEntered(event -> row.setStyle(selectedRowStyle(true)));
            row.setOnMouseExited(event -> {
                if (!candidate.equals(selected))
                    row.setStyle(selectedRowStyle(false));
            });
            completionList.getChildren().add(row);
            if (isSelected)
                selectedRow = row;
        }
        positionAndShowCompletion(candidates.size());

        // Keep the highlighted row visible: with ~100 commands the cycle would otherwise run
        // off the bottom of the popup with no visual feedback.
        if (selectedRow != null) {
            Label row = selectedRow;
            Platform.runLater(() -> {
                double top = row.getBoundsInParent().getMinY();
                double bottom = row.getBoundsInParent().getMaxY();
                double viewTop = completionScroll.getVvalue()
                        * Math.max(1, completionList.getHeight() - completionScroll.getViewportBounds().getHeight());
                double viewHeight = completionScroll.getViewportBounds().getHeight();
                if (top < viewTop || bottom > viewTop + viewHeight) {
                    double contentHeight = Math.max(1,
                            completionList.getHeight() - viewHeight);
                    completionScroll.setVvalue(Math.max(0, Math.min(1, (top - viewHeight / 2) / contentHeight)));
                }
            });
        }
    }

    /// The row currently highlighted in the completion popup, kept for scroll-into-view.
    private Label selectedRow;
    /// Background/text style for one candidate row, using the launcher's theme variables.
    private static String selectedRowStyle(boolean selected) {
        return selected
                ? "-fx-background-color: -monet-primary; -fx-text-fill: -monet-on-primary;"
                : "-fx-background-color: transparent; -fx-text-fill: -monet-on-surface;";
    }

    private void hideCompletionList() {
        if (completionScroll == null)
            return;
        completionScroll.setVisible(false);
        completionList.getChildren().clear();
    }

    /// Sizes and positions the overlay (an unmanaged child is not laid out by the parent, so
    /// it must be placed by hand), then shows it. Being unmanaged is the point: its visibility
    /// can never change the page height, so the outer scroller has nothing to jump about.
    private void positionAndShowCompletion(int candidateCount) {
        int rows = Math.max(1, Math.min(candidateCount, 6));
        double width = consoleStack.getWidth();
        double height = rows * ROW_HEIGHT + 4;
        if (width <= 0)
            width = 600;
        completionScroll.resize(width, height);
        completionScroll.relocate(0, Math.max(0, consoleStack.getHeight() - height));
        completionScroll.setVisible(true);
    }

    /// Sets the field text without letting the change-notification cancel the cycle.
    private void completionFieldSet(String text, int caret) {
        updatingFields = true;
        try {
            commandField.setText(text);
            commandField.positionCaret(Math.max(0, Math.min(caret, text.length())));
        } finally {
            updatingFields = false;
        }
    }

    private void resetCompletion() {
        completionCandidates = List.of();
        completionIndex = -1;
        completionText = "";
        completionBaseText = "";
        completionBase = null;
        hideCompletionList();
    }

    private void showCompletionHint(String message, boolean ok) {
        if (completionHint == null)
            return;
        completionHint.setText(message == null || message.isBlank() ? " " : message);
        completionHint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (ok ? "#1a9b4b" : "#b26a00") + ";");
    }

    private void deleteCurrent() {
        ServerProfile profile = current;
        if (profile == null)
            return;
        ServerManagerPlugin.confirm("删除服务端",
                "确定删除服务端「" + profile.getName() + "」吗？\n插件创建的实例目录会一并删除；外部导入的目录只移除记录。",
                () -> runAsync("删除失败", () -> {
                    service.delete(profile);
                    Platform.runLater(() -> selectProfile(null));
                }));
    }

    private void openCurrentDirectory() {
        if (current != null)
            ServerManagerPlugin.openDirectory(Path.of(current.getDirectory()).toFile());
    }

    /// Brief fade-in of the detail area; runs on the JavaFX thread (selectProfile does).
    private void playDetailFade() {
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(180), detailFields);
        fade.setFromValue(0.25);
        fade.setToValue(1.0);
        fade.play();
    }

    private boolean saveCurrent() {
        if (current == null || updatingFields)
            return current != null;
        try {
            current.setName(nameField.getText().trim());
            current.setPort(Parsers.parsePort(portField.getText()));
            current.setMotd(motdField.getText());
            current.setMinMemory(Parsers.parseMemory(minMemField.getText(), 1024));
            current.setMaxMemory(Parsers.parseMemory(maxMemField.getText(), 2048));
            if (current.getMaxMemory() < current.getMinMemory())
                current.setMaxMemory(current.getMinMemory());
            current.setJavaPath(javaCombo.getEditor().getText().trim());
            current.setOnlineMode(onlineModeCheck.isSelected());
            current.setMaxPlayers(Parsers.parseBounded(maxPlayersField.getText(), 20, 1, 2147483647, "最大玩家数"));
            current.setDifficulty(difficultyBox.getValue() == null ? "normal" : difficultyBox.getValue());
            current.setGamemode(gamemodeBox.getValue() == null ? "survival" : gamemodeBox.getValue());
            current.setViewDistance(Parsers.parseBounded(viewDistanceField.getText(), 10, 3, 32, "视距"));
            current.setSimulationDistance(Parsers.parseBounded(simulationDistanceField.getText(), 10, 5, 32, "模拟距离"));
            current.setPvp(pvpCheck.isSelected());
            current.setAllowFlight(allowFlightCheck.isSelected());
            // The white-list flag is deliberately left untouched: it is owned by 玩家管理,
            // which reads it from server.properties. Copying a checkbox that no longer
            // exists here would silently reset it on every save.
            current.setCommandBlock(commandBlockCheck.isSelected());
            current.setLevelSeed(levelSeedField.getText().trim());
            current.setJvmArgs(jvmArgsField.getText().trim());
            current.setBroadcast(broadcastCheck.isSelected());
            current.setTerracottaRoom(terracottaCheck.isSelected());
            current.setEulaAccepted(eulaCheck.isSelected());
            service.updateProfile(current);
            detailTitle.setText(current.getName());
            reloadServerRows();
            return true;
        } catch (Exception e) {
            ServerManagerPlugin.error("保存设置失败", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ helpers ---

    private void appendConsole(ServerProfile profile, String line) {
        if (!isCurrent(profile))
            return;
        console.appendText(line + "\n");
        console.setScrollTop(Double.MAX_VALUE);
    }

    private void runAsync(String errorTitle, ThrowingTask task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Platform.runLater(() -> ServerManagerPlugin.error(errorTitle,
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            }
        }, "server-manager-action").start();
    }

    private interface ThrowingTask {
        void run() throws Exception;
    }

    /// Shared numeric parsers for the management pages.
    static final class Parsers {
        private Parsers() {
        }

        static int parsePort(String text) {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("端口必须在 1–65535 之间");
            return port;
        }

        static int parseMemory(String text, int fallback) {
            if (text == null || text.isBlank())
                return fallback;
            return Integer.parseInt(text.trim());
        }

        static int parseBounded(String text, int fallback, int min, int max, String label) {
            int value = (text == null || text.isBlank()) ? fallback : Integer.parseInt(text.trim());
            if (value < min || value > max)
                throw new IllegalArgumentException(label + "必须在 " + min + "–" + max + " 之间");
            return value;
        }
    }
}
