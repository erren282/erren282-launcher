package org.dsh.lunarofl;

import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.nio.file.Files;
import java.nio.file.Path;

/// 「Lunar 离线客户端」页面：启动 / 停止 Celestial，配置 jar 与 JDK，并在页面内看输出。
public final class LunarOfflinePage extends VBox {

    private final LunarSettings settings;
    private final CelestialProcess process = CelestialProcess.get();

    private final javafx.scene.control.CheckBox bundledCheck =
            new javafx.scene.control.CheckBox("使用内置核心（推荐，无需另装 Celestial）");
    private final Label coreStatus = new Label(" ");
    private final JFXTextField jarField = new JFXTextField();
    private final JFXComboBox<String> javaBox = new JFXComboBox<>();
    private final JFXTextField jvmArgsField = new JFXTextField();
    private final Label jarStatus = new Label(" ");
    private final Label javaStatus = new Label(" ");
    private final Label runStatus = new Label("● 未运行");
    private final JFXTextField versionIdField = new JFXTextField();
    private final Label installStatus = new Label(" ");
    private final TextArea console = new TextArea();
    private final Button startButton;
    private final Button stopButton;

    private final java.util.Map<String, String> javaDisplayNames = new java.util.HashMap<>();
    private final java.util.function.Consumer<String> logListener = line ->
            Platform.runLater(() -> appendConsole(line));
    private final Runnable stateListener = () -> Platform.runLater(this::refreshRunStatus);

    LunarOfflinePage(Path dataDirectory) {
        this.settings = LunarSettings.load(dataDirectory);
        setSpacing(10);
        setPadding(new Insets(12));

        // ---------- 标题与运行状态 ----------
        Label title = new Label("Lunar 离线客户端");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");
        Label subtitle = new Label("推荐直接在「下载」页面的「Lunar 客户端」标签里下载并一键启动——"
                + "不需要 Celestial。本页面保留给仍想运行 Celestial 的场景（启动 / 停止它、查看输出）。");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
        subtitle.setWrapText(true);
        getChildren().add(new VBox(3, title, subtitle));

        Button downloadTabButton = FXUtils.newRaisedButton("去下载页安装 Lunar");
        downloadTabButton.setMinWidth(Region.USE_PREF_SIZE);
        downloadTabButton.setOnAction(e -> LunarOfflinePlugin.showDownloadTab());
        startButton = FXUtils.newRaisedButton("启动客户端");
        startButton.setMinWidth(Region.USE_PREF_SIZE);
        startButton.setOnAction(e -> startClient());
        stopButton = FXUtils.newRaisedButton("停止");
        stopButton.setMinWidth(Region.USE_PREF_SIZE);
        stopButton.setOnAction(e -> process.stop());
        Button openDirButton = FXUtils.newRaisedButton("打开客户端目录");
        openDirButton.setMinWidth(Region.USE_PREF_SIZE);
        openDirButton.setOnAction(e -> openDirectory(settings.workingDirectory()));
        Button openLogButton = FXUtils.newRaisedButton("打开日志");
        openLogButton.setMinWidth(Region.USE_PREF_SIZE);
        openLogButton.setOnAction(e -> {
            Path log = process.logFile();
            if (log != null && Files.isRegularFile(log))
                org.dsh.lunarofl.LunarOfflinePlugin.openFile(log);
            else
                LunarOfflinePlugin.error("打开日志", "还没有日志文件，先启动一次客户端。");
        });

        runStatus.setStyle("-fx-font-size: 13px; -fx-text-fill: -fx-mid-text-color;");
        HBox actions = new HBox(10, downloadTabButton, startButton, stopButton, openDirButton, openLogButton, runStatus);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(actions);

        // ---------- 设置 ----------
        ComponentList form = new ComponentList();
        form.getContent().add(coreRow());
        form.getContent().add(jarRow());
        form.getContent().add(javaRow());
        form.getContent().add(argsRow());
        getChildren().add(ComponentList.createComponentListTitle("启动设置"));
        getChildren().add(form);

        LineButton saveButton = new LineButton();
        saveButton.setTitle("保存设置");
        saveButton.setSubtitle("记住 Celestial jar、Java 与附加参数，下次打开自动使用");
        saveButton.setOnAction(e -> saveSettings());
        ComponentList saveList = new ComponentList();
        saveList.getContent().add(saveButton);
        getChildren().add(saveList);

        // ---------- 控制台 ----------
        console.setEditable(false);
        console.setWrapText(true);
        console.setStyle("-fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
        console.setPrefHeight(240);
        VBox.setVgrow(console, Priority.ALWAYS);
        // ---------- 安装为 HMCL 版本（直接从版本列表启动 Lunar） ----------
        getChildren().add(ComponentList.createComponentListTitle("安装为 HMCL 版本"));
        getChildren().add(installRow());

        getChildren().add(ComponentList.createComponentListTitle("客户端输出"));
        getChildren().add(console);

        jarField.setText(settings.getJarPath());
        javaBox.getEditor().setText(settings.getJavaPath());
        jvmArgsField.setText(settings.getJvmArgs());
        for (String line : process.history())
            appendConsole(line);

        process.addLogListener(logListener);
        process.addStateListener(stateListener);
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) {
                process.removeLogListener(logListener);
                process.removeStateListener(stateListener);
            }
        });

        refreshJarStatus();
        refreshJavaStatus();
        refreshRunStatus();
        JavaLocator.scanAsync(entries -> Platform.runLater(() -> fillJavaBox(entries)));
    }

    // ------------------------------------------------------------ 表单 ---

    /// 核心来源：内置核心（默认，开箱即用）或外部 Celestial jar。
    private Node coreRow() {
        bundledCheck.setSelected(settings.isUseBundledCore());
        bundledCheck.setOnAction(e -> {
            settings.setUseBundledCore(bundledCheck.isSelected());
            settings.save();
            refreshJarStatus();
        });

        javafx.scene.control.Button reinstall = FXUtils.newRaisedButton("释放 / 更新内置核心");
        reinstall.setMinWidth(Region.USE_PREF_SIZE);
        reinstall.setOnAction(e -> {
            try {
                Path jar = BundledCore.install(LunarOfflinePlugin.dataDirectory());
                coreStatus.setText("● 内置核心已释放：" + jar.getParent());
                coreStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #1a9b4b;");
                LunarOfflinePlugin.log("已释放内置 Celestial 核心：" + jar);
            } catch (Exception ex) {
                LunarOfflinePlugin.error("释放内置核心失败", ex.getMessage());
            }
        });
        javafx.scene.control.Button openCore = FXUtils.newRaisedButton("打开核心目录");
        openCore.setMinWidth(Region.USE_PREF_SIZE);
        openCore.setOnAction(e -> openDirectory(settings.bundledDirectory(LunarOfflinePlugin.dataDirectory())));

        VBox left = new VBox(4, bundledCheck,
                hint("内置核心随插件一起分发，直接启动即可；取消勾选后可使用你自己准备的 Celestial jar"));
        VBox right = new VBox(6, new HBox(8, reinstall, openCore), coreStatus);
        HBox row = new HBox(10, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        HBox.setHgrow(left, Priority.ALWAYS);
        return row;
    }

    /// 把 Celestial 已下载好的 Lunar 客户端安装成 HMCL 的一个版本：
    /// 之后在启动器版本列表里直接选 “Lunar-1.8.9” 启动，不再需要打开 Celestial。
    private Node installRow() {
        versionIdField.setText("Lunar-" + detectGameVersion());
        versionIdField.setPromptText("版本名称，例如 Lunar-1.8.9");
        Button installButton = FXUtils.newRaisedButton("安装 / 更新到当前游戏目录");
        installButton.setMinWidth(Region.USE_PREF_SIZE);
        installButton.setOnAction(e -> installVersion(installButton));

        VBox left = new VBox(4,
                new Label("把 Lunar 客户端装成 HMCL 版本（账号 / 内存 / Java 都走启动器设置）"),
                hint("需要先用「启动客户端」跑一次 Celestial，让它下载好 Lunar 客户端；"
                        + "安装后即可在版本列表中直接启动，无需再打开 Celestial"));
        VBox right = new VBox(6, new HBox(8, versionIdField, installButton), installStatus);
        HBox row = new HBox(10, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(versionIdField, Priority.ALWAYS);
        return row;
    }

    /// 从 Celestial 缓存里读出基础游戏版本（1.8.9 等）用于默认版本名。
    private String detectGameVersion() {
        try {
            return LunarVersionInstaller.read(LunarVersionInstaller.celestialHome().resolve("launch-data.json"))
                    .gameVersion();
        } catch (Exception e) {
            return "1.8.9";
        }
    }

    private void installVersion(Button installButton) {
        String versionId = versionIdField.getText() == null ? "" : versionIdField.getText().trim();
        if (versionId.isBlank()) {
            LunarOfflinePlugin.error("安装 Lunar 版本", "请先填写版本名称，例如 Lunar-1.8.9。");
            return;
        }
        org.jackhuang.hmcl.game.HMCLGameRepository repository =
                org.jackhuang.hmcl.setting.GameDirectoryManager.getSelectedRepository();
        if (repository == null) {
            LunarOfflinePlugin.error("安装 Lunar 版本", "尚未设置游戏目录，请先在启动器中设置游戏目录。");
            return;
        }
        Path gameDirectory = repository.getBaseDirectory();
        installButton.setDisable(true);
        installStatus.setText("● 正在读取 Celestial 启动数据…");
        installStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");

        new Thread(() -> {
            try {
                LunarVersionInstaller.LaunchData data =
                        LunarVersionInstaller.read(LunarVersionInstaller.celestialHome().resolve("launch-data.json"));
                // HMCL 只会列出“继承链完整”的版本：基础版本没装时，Lunar 版本不会出现在列表里
                if (!repository.hasVersion(data.gameVersion())) {
                    Platform.runLater(() -> {
                        installButton.setDisable(false);
                        installStatus.setText("● 缺少基础版本 " + data.gameVersion() + "，请先安装它");
                        installStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #c62828;");
                        LunarOfflinePlugin.error("安装 Lunar 版本",
                                "该 Lunar 客户端基于 Minecraft " + data.gameVersion() + "。\n\n"
                                        + "请先在启动器的「下载」页面安装 " + data.gameVersion()
                                        + "（原版即可），安装完成后再次点击本按钮。");
                    });
                    return;
                }
                LunarVersionInstaller.Result result = LunarVersionInstaller.install(gameDirectory, versionId, data,
                        (step, bytes) -> Platform.runLater(() -> installStatus.setText(
                                "● " + step + "（已复制 " + (bytes / 1024 / 1024) + " MB）")));
                Platform.runLater(() -> {
                    installButton.setDisable(false);
                    installStatus.setText("● 已安装版本「" + result.versionId() + "」（" + result.libraries()
                            + " 个库，复制 " + (result.bytes() / 1024 / 1024) + " MB）。"
                            + "如未显示，请在版本列表中刷新。");
                    installStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #1a9b4b;");
                });
                LunarOfflinePlugin.log("已安装 Lunar 版本 " + result.versionId() + " 到 " + gameDirectory
                        + "（需要基础版本 " + result.baseVersion() + "）");
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage();
                Platform.runLater(() -> {
                    installButton.setDisable(false);
                    installStatus.setText("● 安装失败：" + message.split("\n")[0]);
                    installStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #c62828;");
                });
                LunarOfflinePlugin.error("安装 Lunar 版本失败", message);
            }
        }, "lunar-offline-install").start();
    }

    private Label hint(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");
        label.setWrapText(true);
        return label;
    }

    private Node jarRow() {
        Button choose = FXUtils.newRaisedButton("选择");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        choose.setOnAction(e -> chooseJar());
        Button detect = FXUtils.newRaisedButton("自动检测");
        detect.setMinWidth(Region.USE_PREF_SIZE);
        detect.setOnAction(e -> {
            String found = LunarSettings.detectJar();
            if (found.isBlank()) {
                LunarOfflinePlugin.error("自动检测", "没有在常见位置找到 celestial jar，请手动选择。");
                return;
            }
            jarField.setText(found);
            saveSettings();
        });

        jarField.setPromptText("celestial-*.jar 的完整路径");
        jarField.textProperty().addListener((o, oldText, newText) -> refreshJarStatus());

        HBox buttons = new HBox(8, choose, detect);
        VBox right = new VBox(6, buttons, jarStatus);
        HBox.setHgrow(jarField, Priority.ALWAYS);
        HBox row = new HBox(10, jarField, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return labelledRow("Celestial jar", "客户端启动器 jar（文件名含 celestial）", row);
    }

    private Node javaRow() {
        Button scan = FXUtils.newRaisedButton("扫描");
        scan.setMinWidth(Region.USE_PREF_SIZE);
        scan.setOnAction(e -> {
            scan.setDisable(true);
            JavaLocator.scanAsync(entries -> Platform.runLater(() -> {
                fillJavaBox(entries);
                scan.setDisable(false);
            }));
        });
        Button choose = FXUtils.newRaisedButton("选择");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        choose.setOnAction(e -> chooseJava());

        javaBox.setEditable(true);
        javaBox.setMaxWidth(Double.MAX_VALUE);
        javaBox.setPromptText("选择 java.exe（Celestial 需要 JDK 21）");
        javaBox.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : javaDisplayNames.getOrDefault(item, item));
            }
        });
        javaBox.getEditor().textProperty().addListener((o, oldText, newText) -> refreshJavaStatus());
        HBox.setHgrow(javaBox, Priority.ALWAYS);
        HBox buttons = new HBox(8, scan, choose);
        VBox right = new VBox(6, buttons, javaStatus);
        HBox row = new HBox(10, javaBox, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return labelledRow("Java", "Celestial 需要 JDK 21（脚本会用 javac，JRE 不行）", row);
    }

    private Node argsRow() {
        jvmArgsField.setPromptText("可选，例如 -Xmx2G -Dcelestial.branch=master");
        return labelledRow("附加 JVM 参数", "传递给 Celestial 进程本身的参数，留空即可", jvmArgsField);
    }

    /// 与启动器一致的“标签 + 控件”两列表单行。
    private Node labelledRow(String label, String hint, Node field) {
        Label name = new Label(label);
        name.setMinWidth(90);
        Label description = new Label(hint);
        description.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");
        description.setWrapText(true);

        VBox left = new VBox(2, name, description);
        HBox row = new HBox(10, left, field);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        GridPane.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private void fillJavaBox(java.util.List<JavaLocator.JavaEntry> entries) {
        javaDisplayNames.clear();
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (JavaLocator.JavaEntry entry : entries) {
            javaDisplayNames.put(entry.path(), entry.display());
            paths.add(entry.path());
        }
        // JDK 21 排最前，方便直接选
        paths.sort((a, b) -> Boolean.compare(isJdk21(b), isJdk21(a)));
        javaBox.getItems().setAll(paths);
        if (javaBox.getEditor().getText() == null || javaBox.getEditor().getText().isBlank()) {
            for (JavaLocator.JavaEntry entry : entries) {
                if (entry.isJdk21()) {
                    javaBox.getEditor().setText(entry.path());
                    break;
                }
            }
        }
        refreshJavaStatus();
    }

    private boolean isJdk21(String path) {
        String text = javaDisplayNames.get(path);
        return text != null && text.startsWith("JDK") && text.contains("21");
    }

    // ------------------------------------------------------------ 行为 ---

    private void saveSettings() {
        settings.setJarPath(jarField.getText());
        settings.setJavaPath(javaBox.getEditor().getText());
        settings.setJvmArgs(jvmArgsField.getText());
        settings.save();
        refreshJarStatus();
        refreshJavaStatus();
        LunarOfflinePlugin.log("已保存 Lunar 客户端设置：" + settings.getJarPath());
    }

    private void startClient() {
        saveSettings();

        Path jar;
        try {
            jar = settings.resolveJar(LunarOfflinePlugin.dataDirectory());
        } catch (Exception e) {
            LunarOfflinePlugin.error("启动客户端", (e.getMessage() == null ? String.valueOf(e) : e.getMessage())
                    + "\n\n可以勾选「使用内置核心」，直接使用插件自带的 Celestial。");
            return;
        }
        String javaPath = settings.getJavaPath();
        if (javaPath.isBlank() || !Files.isRegularFile(Path.of(javaPath))) {
            LunarOfflinePlugin.error("启动客户端", "找不到 Java，请选择本机的 JDK 21（不是 JRE）。");
            return;
        }

        String version = JavaLocator.probeVersion(javaPath);
        boolean jdk = Files.isRegularFile(Path.of(javaPath).resolveSibling("javac.exe"));
        if (!version.startsWith("21") || !jdk) {
            // Celestial 对 JDK 21 有硬性要求（脚本里会直接用 javac），先提醒再让用户决定
            Controllers.confirm("当前 Java 可能不满足要求：\n\n" + javaPath + "\n版本：" + (version.isBlank() ? "未知" : version)
                            + (jdk ? "（JDK）" : "（缺少 javac，可能是 JRE）")
                            + "\n\nCelestial 需要 JDK 21，否则可能启动失败。仍要继续吗？",
                    "Java 版本提醒",
                    () -> doStart(jar, javaPath), null);
            return;
        }
        doStart(jar, javaPath);
    }

    private void doStart(Path jar, String javaPath) {
        try {
            process.start(javaPath, jar, LunarOfflinePlugin.dataDirectory(), settings.getJvmArgs());
            LunarOfflinePlugin.log("启动 Lunar 离线客户端：" + jar);
        } catch (Exception e) {
            LunarOfflinePlugin.error("启动客户端失败", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
        refreshRunStatus();
    }

    private void chooseJar() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择 Celestial jar");
        Path current = settings.workingDirectory();
        if (Files.isDirectory(current))
            chooser.setInitialDirectory(current.toFile());
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Celestial jar", "*.jar"));
        java.io.File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null)
            return;
        jarField.setText(file.getAbsolutePath());
        saveSettings();
    }

    private void chooseJava() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择 java.exe（JDK 21）");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Java 可执行文件", "java.exe", "java"));
        java.io.File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null)
            return;
        javaBox.getEditor().setText(file.getAbsolutePath());
        saveSettings();
    }

    private void openDirectory(Path directory) {
        try {
            if (!Files.isDirectory(directory)) {
                LunarOfflinePlugin.error("打开目录", "目录不存在：" + directory);
                return;
            }
            java.awt.Desktop.getDesktop().open(directory.toFile());
        } catch (Exception e) {
            LunarOfflinePlugin.error("打开目录失败", e.getMessage());
        }
    }

    // ------------------------------------------------------------ 状态 ---

    private void refreshJarStatus() {
        boolean bundled = settings.isUseBundledCore();
        jarField.setDisable(bundled);
        if (bundled) {
            BundledCore.Core core = BundledCore.describe();
            if (core == null || !BundledCore.available()) {
                coreStatus.setText("● 插件包内未找到内置核心，请改用外部 Celestial jar");
                coreStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #c62828;");
            } else {
                Path installed = settings.bundledDirectory(LunarOfflinePlugin.dataDirectory())
                        .resolve(core.jar());
                coreStatus.setText("● 内置核心 Celestial " + core.version()
                        + (Files.isRegularFile(installed) ? "（已释放，可直接启动）" : "（首次启动时自动释放）"));
                coreStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #1a9b4b;");
            }
            jarStatus.setText(" ");
            return;
        }
        coreStatus.setText("● 使用外部 Celestial jar（需自行准备与更新）");
        coreStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");

        Path jar = jarField.getText() == null || jarField.getText().isBlank()
                ? null : Path.of(jarField.getText().trim());
        if (jar == null || !Files.isRegularFile(jar)) {
            jarStatus.setText("● 未找到该文件，请选择正确的 celestial jar");
            jarStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #c62828;");
            return;
        }
        String name = jar.getFileName().toString();
        boolean looksRight = name.toLowerCase(java.util.Locale.ROOT).contains("celestial");
        jarStatus.setText(looksRight ? "● 已就绪（" + name + "）" : "● 文件名不含 celestial，可能不是客户端 jar");
        jarStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (looksRight ? "#1a9b4b" : "#b26a00") + ";");
    }

    private void refreshJavaStatus() {
        String path = javaBox.getEditor().getText();
        if (path == null || path.isBlank()) {
            javaStatus.setText("● 未选择 Java");
            javaStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #c62828;");
            return;
        }
        if (!Files.isRegularFile(Path.of(path.trim()))) {
            javaStatus.setText("● 文件不存在");
            javaStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #c62828;");
            return;
        }
        String display = javaDisplayNames.get(path.trim());
        if (display != null) {
            boolean ok = display.startsWith("JDK") && display.contains("21");
            javaStatus.setText(ok ? "● 符合要求（JDK 21）" : "● " + display.split("  ·  ")[0] + "，Celestial 建议使用 JDK 21");
            javaStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (ok ? "#1a9b4b" : "#b26a00") + ";");
        } else {
            javaStatus.setText("● 已选择（版本未探测，启动时会检查）");
            javaStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        }
    }

    private void refreshRunStatus() {
        boolean running = process.isRunning();
        if (running) {
            long seconds = Math.max(0, (System.currentTimeMillis() - process.startedAt()) / 1000);
            runStatus.setText("● 运行中（已运行 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒，PID "
                    + process.pid() + "）");
            runStatus.setStyle("-fx-font-size: 13px; -fx-text-fill: #1a9b4b;");
        } else {
            runStatus.setText("● 未运行");
            runStatus.setStyle("-fx-font-size: 13px; -fx-text-fill: -fx-mid-text-color;");
        }
        startButton.setDisable(running);
        stopButton.setDisable(!running);
    }

    private void appendConsole(String line) {
        console.appendText(line + "\n");
        console.setScrollTop(Double.MAX_VALUE);
    }

    void dispose() {
        process.removeLogListener(logListener);
        process.removeStateListener(stateListener);
    }
}
