package org.dsh.servermanager;

import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginMenuService;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// The "download a server" wizard, contributed as a tab of the launcher's download
/// page and styled with the launcher's own components.
public final class ServerDownloadPage extends javafx.scene.control.ScrollPane {

    private final ServerManagerService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "server-manager-download");
        thread.setDaemon(true);
        return thread;
    });

    private JFXTextField newNameField;
    private JFXComboBox<String> typeBox;
    private JFXComboBox<String> versionBox;
    private javafx.scene.control.Button loadVersionsButton;
    private Label versionsLabel;
    private JFXTextField customVersionField;
    private HBox importRow;
    private Label importDirLabel;
    private Label importJarLabel;
    private JFXTextField newPortField;
    private JFXTextField newMotdField;
    private JFXTextField newMinMemField;
    private JFXTextField newMaxMemField;
    private JFXComboBox<String> newJavaCombo;
    private javafx.scene.control.Button newScanJavaButton;
    private volatile boolean scanBusy;
    private final Map<String, String> javaDisplayNames = new java.util.HashMap<>();
    private javafx.scene.control.Label coreRepairLabel;
    private JFXComboBox<String> coreRepairBox;
    private JFXCheckBox newEulaCheck;
    private JFXCheckBox newBroadcastCheck;
    private ProgressBar progressBar;
    private Label progressLabel;
    private javafx.scene.control.Button createButton;

    private Path importDirectory;
    private String importJar;
    private List<ServerVersions.VersionEntry> vanillaVersions;

    public ServerDownloadPage(ServerManagerService service) {
        this.service = service;

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.setMaxWidth(Double.MAX_VALUE);

        root.getChildren().add(ComponentList.createComponentListTitle("下载 / 新建服务端"));

        GridPane form = buildForm();
        root.getChildren().add(form);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        createButton = FXUtils.newRaisedButton("创建（自动下载）");
        createButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        // never let the primary action shrink into nothing on a narrow window
        createButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        createButton.setOnAction(e -> createServer());
        Label hint = new Label("创建完成后可在主页面的「服务端管理」中一键开设并广播。");
        hint.setStyle("-fx-text-fill: #666;");
        hint.setWrapText(true);
        HBox.setHgrow(hint, Priority.ALWAYS);
        actions.getChildren().addAll(createButton, hint);
        root.getChildren().add(actions);

        // the java combo fills asynchronously
        scanJavas();

        // a scrollable viewport keeps the page's minimum width small, so the
        // download page never overflows (which would clip the sidebar)
        setContent(root);
        setFitToWidth(true);
        FXUtils.smoothScrolling(this);
        // First entry must show the top of the form. Setting vvalue before the content has been
        // laid out does not stick (the layout pass overwrites it), so the reset is deferred.
        javafx.application.Platform.runLater(() -> setVvalue(0));
    }

    private GridPane buildForm() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(4, 0, 4, 0));
        // keep the label column readable on narrow windows; the field column
        // takes the remaining width instead of crushing the labels to "…"
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        form.getColumnConstraints().addAll(labelColumn, fieldColumn);

        int row = 0;
        form.add(new Label("名称"), 0, row);
        newNameField = new JFXTextField();
        newNameField.setPromptText("给这个服务端起个名字");
        form.add(newNameField, 1, row++);

        form.add(new Label("服务端类型"), 0, row);
        typeBox = new JFXComboBox<>();
        typeBox.getItems().setAll("原版 (Mojang)", "Paper", "导入本地服务端");
        typeBox.getSelectionModel().selectFirst();
        typeBox.setMaxWidth(Double.MAX_VALUE);
        typeBox.setOnAction(e -> onCreateTypeChanged());
        form.add(typeBox, 1, row++);

        form.add(new Label("游戏版本"), 0, row);
        HBox versionRow = new HBox(8);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        versionBox = new JFXComboBox<>();
        versionBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(versionBox, Priority.ALWAYS);
        versionBox.setDisable(true);
        loadVersionsButton = FXUtils.newRaisedButton("获取版本列表");
        loadVersionsButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        loadVersionsButton.setOnAction(e -> loadVersions());
        versionRow.getChildren().addAll(versionBox, loadVersionsButton);
        form.add(versionRow, 1, row++);

        versionsLabel = new Label(" ");
        versionsLabel.setStyle("-fx-text-fill: #666;");
        form.add(versionsLabel, 1, row++);

        form.add(new Label("版本号（导入可选）"), 0, row);
        customVersionField = new JFXTextField();
        customVersionField.setPromptText("例如 1.20.4");
        form.add(customVersionField, 1, row++);

        coreRepairLabel = new Label("核心修复");
        form.add(coreRepairLabel, 0, row);
        coreRepairBox = new JFXComboBox<>();
        coreRepairBox.getItems().setAll("不修复（保持现状）",
                "下载 / 修复原版核心 server.jar",
                "下载 / 修复 Paper 核心");
        coreRepairBox.getSelectionModel().selectFirst();
        coreRepairBox.setMaxWidth(Double.MAX_VALUE);
        form.add(coreRepairBox, 1, row++);

        form.add(new Label("服务端目录"), 0, row);
        importRow = new HBox(8);
        importRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.control.Button chooseDirButton = FXUtils.newRaisedButton("选择目录…");
        chooseDirButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        chooseDirButton.setOnAction(e -> chooseImportDirectory());
        javafx.scene.control.Button chooseJarButton = FXUtils.newRaisedButton("选择 jar…");
        chooseJarButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        chooseJarButton.setOnAction(e -> chooseImportJar());
        importDirLabel = new Label("未选择");
        importJarLabel = new Label("未选择");
        for (Label label : new Label[]{importDirLabel, importJarLabel}) {
            label.setStyle("-fx-text-fill: #666;");
            label.setMaxWidth(120);
            label.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
        }
        importRow.getChildren().addAll(chooseDirButton, importDirLabel, chooseJarButton, importJarLabel);
        form.add(importRow, 1, row++);

        form.add(new Label("端口"), 0, row);
        newPortField = new JFXTextField();
        newPortField.setPrefWidth(120);
        newPortField.setText("25565");
        form.add(newPortField, 1, row++);

        form.add(new Label("MOTD"), 0, row);
        newMotdField = new JFXTextField();
        newMotdField.setText("A Minecraft Server");
        form.add(newMotdField, 1, row++);

        form.add(new Label("内存 (MB)"), 0, row);
        HBox newMemoryRow = new HBox(8);
        newMemoryRow.setAlignment(Pos.CENTER_LEFT);
        newMinMemField = new JFXTextField();
        newMaxMemField = new JFXTextField();
        newMinMemField.setPrefWidth(90);
        newMaxMemField.setPrefWidth(90);
        newMinMemField.setText("1024");
        newMaxMemField.setText("2048");
        newMemoryRow.getChildren().addAll(new Label("最小"), newMinMemField, new Label("最大"), newMaxMemField);
        form.add(newMemoryRow, 1, row++);

        form.add(new Label("Java 路径"), 0, row);
        HBox newJavaRow = new HBox(8);
        newJavaRow.setAlignment(Pos.CENTER_LEFT);
        newJavaCombo = new JFXComboBox<>();
        newJavaCombo.setEditable(true);
        newJavaCombo.setPromptText("留空使用自带 Java；点“扫描”自动查找本机 Java");
        HBox.setHgrow(newJavaCombo, Priority.ALWAYS);
        newJavaCombo.setMaxWidth(Double.MAX_VALUE);
        newJavaCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : javaDisplayNames.getOrDefault(item, item));
            }
        });
        javafx.scene.control.Button newChooseJavaButton = FXUtils.newRaisedButton("选择");
        newChooseJavaButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        newChooseJavaButton.setOnAction(e -> chooseJava());
        newScanJavaButton = FXUtils.newRaisedButton("扫描");
        newScanJavaButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        newScanJavaButton.setOnAction(e -> scanJavas());
        javafx.scene.control.Button newDownloadJavaButton = FXUtils.newRaisedButton("下载 Java");
        newDownloadJavaButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        newDownloadJavaButton.setOnAction(e -> openJavaDownload());
        newJavaRow.getChildren().addAll(newJavaCombo, newChooseJavaButton, newScanJavaButton, newDownloadJavaButton);
        form.add(newJavaRow, 1, row++);

        newEulaCheck = new JFXCheckBox("已阅读并同意 Minecraft EULA");
        form.add(newEulaCheck, 1, row++);

        newBroadcastCheck = new JFXCheckBox("创建后启动时自动向局域网广播");
        newBroadcastCheck.setSelected(true);
        form.add(newBroadcastCheck, 1, row++);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressLabel = new Label(" ");
        progressLabel.setStyle("-fx-text-fill: #666;");
        form.add(progressBar, 1, row++);
        form.add(progressLabel, 1, row++);

        return form;
    }

    private void onCreateTypeChanged() {
        String type = typeBox.getValue();
        boolean custom = "导入本地服务端".equals(type);
        versionBox.setDisable(custom);
        loadVersionsButton.setDisable(custom);
        customVersionField.setDisable(!custom);
        importRow.setVisible(custom);
        importRow.setManaged(custom);
        coreRepairLabel.setVisible(custom);
        coreRepairLabel.setManaged(custom);
        coreRepairBox.setVisible(custom);
        coreRepairBox.setManaged(custom);
        createButton.setText(custom ? "导入并登记" : "创建（自动下载）");
    }

    private void loadVersions() {
        loadVersionsButton.setDisable(true);
        versionsLabel.setText("正在获取版本列表…");
        executor.execute(() -> {
            try {
                String type = typeBox.getValue();
                if ("Paper".equals(type)) {
                    List<String> versions = ServerVersions.fetchPaperVersions();
                    Platform.runLater(() -> {
                        versionBox.getItems().setAll(versions);
                        versionBox.getSelectionModel().selectFirst();
                        versionsLabel.setText("共 " + versions.size() + " 个 Paper 版本，选择一个后点击创建。");
                        loadVersionsButton.setDisable(false);
                    });
                } else {
                    List<ServerVersions.VersionEntry> versions = ServerVersions.fetchVanillaVersions();
                    vanillaVersions = versions;
                    Platform.runLater(() -> {
                        versionBox.getItems().clear();
                        for (ServerVersions.VersionEntry entry : versions)
                            versionBox.getItems().add(entry.getId() + "  [" + entry.getType() + "]");
                        versionBox.getSelectionModel().selectFirst();
                        versionsLabel.setText("共 " + versions.size() + " 个版本（release / snapshot / 旧版本）。");
                        loadVersionsButton.setDisable(false);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    versionsLabel.setText("版本列表获取失败：" + e.getMessage());
                    loadVersionsButton.setDisable(false);
                });
            }
        });
    }

    /// Opens the launcher's Java management page, where a runtime can be downloaded;
    /// run the scan afterwards to pick it up.
    private void openJavaDownload() {
        org.jackhuang.hmcl.ui.Controllers.getSettingsPage().showJavaManagement();
        org.jackhuang.hmcl.ui.Controllers.navigate(org.jackhuang.hmcl.ui.Controllers.getSettingsPage());
    }

    /// Scans the computer for Java installations in the background (full disk scan with
    /// progress on the button, so it really looks everywhere instead of a few known folders).
    private void scanJavas() {
        if (scanBusy)
            return;
        scanBusy = true;
        newScanJavaButton.setDisable(true);
        executor.execute(() -> {
            List<JavaScanner.JavaInfo> javas = JavaScanner.scanDeep(service.getDataDirectory(), checked ->
                    Platform.runLater(() -> {
                        if (newScanJavaButton != null)
                            newScanJavaButton.setText("扫描中… " + checked);
                    }));
            Platform.runLater(() -> {
                scanBusy = false;
                newScanJavaButton.setDisable(false);
                newScanJavaButton.setText("扫描");
                javaDisplayNames.clear();
                List<String> paths = new ArrayList<>();
                for (JavaScanner.JavaInfo info : javas) {
                    javaDisplayNames.put(info.getPath(), info.toString());
                    paths.add(info.getPath());
                }
                newJavaCombo.getItems().setAll(paths);
                if (!javas.isEmpty() && (newJavaCombo.getEditor().getText() == null || newJavaCombo.getEditor().getText().isBlank()))
                    newJavaCombo.getEditor().setText(javas.get(0).getPath());
            });
        });
    }

    /// Opens a file chooser to pick the java executable for the new instance.
    private void chooseJava() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择 Java 可执行文件（java.exe）");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                "Java", windows ? new String[]{"java.exe", "*.exe"} : new String[]{"java", "*"}));
        java.io.File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null)
            newJavaCombo.getEditor().setText(file.getAbsolutePath());
    }

    private void chooseImportDirectory() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("选择已有的服务端目录");
        File dir = chooser.showDialog(getScene() != null ? getScene().getWindow() : null);
        if (dir != null) {
            importDirectory = dir.toPath();
            importDirLabel.setText(dir.getAbsolutePath());
        }
    }

    private void chooseImportJar() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择服务端启动 jar");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("服务端 jar", "*.jar"));
        File jar = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (jar != null) {
            importJar = jar.getName();
            importJarLabel.setText(importJar);
            if (importDirectory == null) {
                importDirectory = jar.getParentFile().toPath();
                importDirLabel.setText(importDirectory.toString());
            }
        }
    }

    private void createServer() {
        final String name = newNameField.getText().trim();
        if (name.isEmpty()) {
            ServerManagerPlugin.error("创建失败", "请先给服务端起个名字。");
            return;
        }
        final String type = typeBox.getValue();
        final int port;
        final int minMemory;
        final int maxMemory;
        try {
            port = ServerManagerPage.Parsers.parsePort(newPortField.getText());
            minMemory = ServerManagerPage.Parsers.parseMemory(newMinMemField.getText(), 1024);
            maxMemory = ServerManagerPage.Parsers.parseMemory(newMaxMemField.getText(), 2048);
        } catch (Exception e) {
            ServerManagerPlugin.error("创建失败", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            return;
        }
        final String motd = newMotdField.getText();
        final String javaPath = newJavaCombo.getEditor().getText().trim();
        final boolean eula = newEulaCheck.isSelected();
        final boolean broadcast = newBroadcastCheck.isSelected();

        if (!eula) {
            ServerManagerPlugin.error("创建失败", "需要先同意 Minecraft EULA 才能启动服务端。");
            return;
        }

        setCreateBusy(true);
        executor.execute(() -> {
            ServerVersions.Progress progress = (done, total) -> Platform.runLater(() -> {
                progressBar.setVisible(true);
                if (total > 0) {
                    progressBar.setProgress((double) done / total);
                    progressLabel.setText(String.format("下载中 %.1f / %.1f MB", done / 1048576.0, total / 1048576.0));
                } else {
                    progressBar.setProgress(-1);
                    progressLabel.setText("下载中 " + done / 1048576.0 + " MB");
                }
            });
            try {
                ServerProfile profile;
                if ("导入本地服务端".equals(type)) {
                    if (importDirectory == null)
                        throw new IOException("请选择服务端目录");
                    String jar = importJar == null ? "server.jar" : importJar;
                    ServerManagerService.CoreRepair repair =
                            switch (coreRepairBox.getSelectionModel().getSelectedIndex()) {
                                case 1 -> ServerManagerService.CoreRepair.VANILLA;
                                case 2 -> ServerManagerService.CoreRepair.PAPER;
                                default -> ServerManagerService.CoreRepair.NONE;
                            };
                    profile = service.importLocal(name, importDirectory, jar,
                            customVersionField.getText().trim(), port, motd, minMemory, maxMemory,
                            javaPath, eula, broadcast, repair, progress);
                } else if ("Paper".equals(type)) {
                    String version = selectedVersionId();
                    if (version == null)
                        throw new IOException("请先获取版本列表并选择版本");
                    profile = service.createPaper(name, version, port, motd, minMemory, maxMemory,
                            javaPath, eula, broadcast, progress);
                } else {
                    ServerVersions.VersionEntry entry = selectedVanillaEntry();
                    if (entry == null)
                        throw new IOException("请先获取版本列表并选择版本");
                    profile = service.createVanilla(name, entry.getId(), entry.getUrl(), port, motd,
                            minMemory, maxMemory, javaPath, eula, broadcast, progress);
                }

                Platform.runLater(() -> {
                    setCreateBusy(false);
                    progressBar.setVisible(false);
                    progressLabel.setText(" ");
                    ServerManagerPlugin.info("创建成功",
                            "服务端「" + profile.getName() + "」已创建。");
                    ServerManagerPlugin.showServerManager();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setCreateBusy(false);
                    progressLabel.setText(" ");
                    progressBar.setVisible(false);
                    ServerManagerPlugin.error("创建失败", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
                });
            }
        });
    }

    private String selectedVersionId() {
        String selected = versionBox.getValue();
        return selected == null ? null : selected.split("  \\[")[0].trim();
    }

    private ServerVersions.VersionEntry selectedVanillaEntry() {
        String selected = versionBox.getValue();
        if (selected == null || vanillaVersions == null)
            return null;
        String id = selected.split("  \\[")[0].trim();
        for (ServerVersions.VersionEntry entry : vanillaVersions)
            if (entry.getId().equals(id))
                return entry;
        return null;
    }

    private void setCreateBusy(boolean busy) {
        createButton.setDisable(busy);
        loadVersionsButton.setDisable(busy);
        newNameField.setDisable(busy);
        typeBox.setDisable(busy);
        versionBox.setDisable(busy || "导入本地服务端".equals(typeBox.getValue()));
    }
}
