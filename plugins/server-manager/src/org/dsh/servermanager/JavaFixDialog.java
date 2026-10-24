package org.dsh.servermanager;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginContext;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.DialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/// The dialog shown when the Java runtime configured for a server cannot run it.
///
/// <p>Besides explaining the problem it offers the fixes the user can act on immediately:
/// switching to another already-installed Java, or downloading a suitable one and switching
/// to it in one step. Downloading reuses the launcher's own task dialog, so progress,
/// cancellation and error reporting match the rest of HMCL.</p>
@NotNullByDefault
public final class JavaFixDialog extends DialogPane {

    private final ServerProfile profile;
    private final ServerManagerService service;
    private final PluginContext context;
    private final JavaCompatibility.Verdict verdict;
    private final Consumer<ServerProfile> onFixed;
    /// Non-null only for advisory (WARNING) verdicts, where starting anyway is a valid choice.
    private final @Nullable Runnable onContinue;
    /// Called after the profile's Java changed, so the page can re-read its fields. Without
    /// this the page's Java combo would keep the old value and the next save would revert it.
    private final Runnable onProfileChanged;

    private final VBox body = new VBox(12);
    private final Label statusLabel = new Label();
    private final SpinnerPane installPane = new SpinnerPane();
    private final JFXButton installButton = new JFXButton();
    private final JFXButton chooseButton = new JFXButton("选择已安装的 Java…");
    private final JFXButton continueButton = new JFXButton("仍然启动");

    private JavaFixDialog(ServerProfile profile, ServerManagerService service, PluginContext context,
                          JavaCompatibility.Verdict verdict, Consumer<ServerProfile> onFixed,
                          @Nullable Runnable onContinue, Runnable onProfileChanged) {
        this.profile = profile;
        this.service = service;
        this.context = context;
        this.verdict = verdict;
        this.onFixed = onFixed;
        this.onContinue = onContinue;
        this.onProfileChanged = onProfileChanged;

        setTitle(verdict.getSeverity() == JavaCompatibility.Severity.BLOCKING
                ? "无法启动：Java 版本不匹配"
                : "提示：Java 版本可能不合适");

        buildBody();
        setBody(body);
        setActions(statusLabel, installPane, chooseButton, cancelButton);
        cancelButton.setText(onContinue != null ? "取消" : "取消启动");

        // The accept button of the base class is not used: this dialog has two concrete
        // actions of its own, so the generic "OK" is hidden.
        acceptPane.setVisible(false);
        acceptPane.setManaged(false);
        chooseButton.getStyleClass().add("dialog-cancel");
        chooseButton.setOnAction(e -> chooseInstalledJava());
        installButton.getStyleClass().add("dialog-accept");
        installButton.setOnAction(e -> startDownload());
        installPane.getStyleClass().add("small-spinner-pane");
        installPane.setContent(installButton);

        if (onContinue != null) {
            continueButton.getStyleClass().add("dialog-accept");
            continueButton.setOnAction(e -> {
                fireEvent(new DialogCloseEvent());
                onContinue.run();
            });
            getActions().add(0, continueButton);
        }

        statusLabel.getStyleClass().add("warning-label");
    }

    /// Shows the fix dialog for `verdict`; `onFixed` runs after the profile got a new Java.
    public static void show(ServerProfile profile, ServerManagerService service, PluginContext context,
                            JavaCompatibility.Verdict verdict, Consumer<ServerProfile> onFixed) {
        show(profile, service, context, verdict, onFixed, null, () -> {});
    }

    /// Shows the fix dialog, additionally offering `onContinue` as "start anyway".
    ///
    /// @param onProfileChanged invoked after the profile's Java path changed, so the caller can
    ///        refresh any cached UI fields
    public static void show(ServerProfile profile, ServerManagerService service, PluginContext context,
                            JavaCompatibility.Verdict verdict, Consumer<ServerProfile> onFixed,
                            @Nullable Runnable onContinue, Runnable onProfileChanged) {
        Platform.runLater(() -> Controllers.dialog(
                new JavaFixDialog(profile, service, context, verdict, onFixed, onContinue, onProfileChanged)));
    }

    /// Explains what was detected, in plain language, above the offered actions.
    private void buildBody() {
        Label headline = new Label(verdict.getHeadline());
        headline.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label detail = new Label(verdict.getDetail());
        detail.setWrapText(true);
        detail.setMaxWidth(520);

        Label current = new Label("当前 Java：" + (verdict.getActualVersion().isBlank()
                ? "未知" : verdict.getActualVersion())
                + (verdict.getJavaPath().isBlank() ? "" : "（" + verdict.getJavaPath() + "）"));
        current.setWrapText(true);
        current.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        body.getChildren().setAll(headline, detail, current);
        body.setPadding(new Insets(0, 0, 4, 0));

        if (verdict.getSeverity() == JavaCompatibility.Severity.WARNING)
            installButton.setText("下载 Java 8 并切换");
        else
            installButton.setText("下载并安装 Java " + verdict.getRecommendedMajor() + "，然后切换");
    }

    // ------------------------------------------------------------ actions ---

    /// Downloads the recommended Java with the launcher's task dialog, then points the profile
    /// at it and restarts the server.
    ///
    /// <p>The completion handler must be attached before the task is handed to the launcher,
    /// because starting a task runs it immediately.</p>
    private void startDownload() {
        int major = verdict.getRecommendedMajor();
        fireEvent(new DialogCloseEvent());

        context.logInfo("开始为服务端「" + profile.getName() + "」下载 Java " + major
                + "（" + JavaDownloader.describe(major) + "）");

        Task<Path> download;
        try {
            download = JavaDownloader.download(DownloadProviders.getDownloadProvider(), major);
        } catch (Exception e) {
            report("下载 Java 失败", e);
            return;
        }

        Task<Void> pipeline = download.whenComplete(Schedulers.javafx(), (javaBinary, error) -> {
            if (error != null) {
                context.logError("下载 Java " + major + " 失败", error);
                Controllers.dialog(DownloadProviders.localizeErrorMessage(error), "下载 Java 失败",
                        MessageDialogPane.MessageType.ERROR);
                return;
            }
            applyJavaAndRestart(javaBinary);
        });

        Controllers.taskDialog(pipeline, "正在下载 Java " + major, TaskCancellationAction.NORMAL);
    }

    /// Lets the user pick among the runtimes already installed on the machine.
    private void chooseInstalledJava() {
        fireEvent(new DialogCloseEvent());
        JavaPickerDialog.show(profile, service, context, verdict, onFixed, onProfileChanged);
    }

    /// Points the profile at `javaBinary`, persists it and starts the server again.
    void applyJavaAndRestart(Path javaBinary) {
        String path = javaBinary.toString();
        profile.setJavaPath(path);
        try {
            service.updateProfile(profile);
        } catch (Exception e) {
            report("保存 Java 设置失败", e);
            return;
        }
        context.logInfo("服务端「" + profile.getName() + "」的 Java 已切换为 " + path);
        onProfileChanged.run();
        Controllers.dialog("已把服务端「" + profile.getName() + "」的 Java 切换为：\n" + path
                        + "\n\n现在重新启动服务端。",
                "Java 已切换", MessageDialogPane.MessageType.SUCCESS,
                () -> onFixed.accept(profile));
    }

    private void report(String title, Throwable error) {
        String message = error.getMessage() == null ? String.valueOf(error) : error.getMessage();
        context.logError(title, error);
        Controllers.dialog(message, title, MessageDialogPane.MessageType.ERROR);
    }

    // ------------------------------------------------------------- picker ---

    /// A second dialog listing every installed runtime that could run this server.
    private static final class JavaPickerDialog extends DialogPane {

        private final ServerProfile profile;
        private final ServerManagerService service;
        private final PluginContext context;
        private final JavaCompatibility.Verdict verdict;
        private final Consumer<ServerProfile> onFixed;
        private final Runnable onProfileChanged;
        private final VBox list = new VBox(6);
        private final Label hint = new Label("正在扫描已安装的 Java…");

        JavaPickerDialog(ServerProfile profile, ServerManagerService service, PluginContext context,
                         JavaCompatibility.Verdict verdict, Consumer<ServerProfile> onFixed,
                         Runnable onProfileChanged) {
            this.profile = profile;
            this.service = service;
            this.context = context;
            this.verdict = verdict;
            this.onFixed = onFixed;
            this.onProfileChanged = onProfileChanged;

            setTitle("选择 Java");
            setBody(new VBox(8, hint, list));
            setActions(warningLabel, acceptPane, cancelButton);
            acceptPane.setVisible(false);
            acceptPane.setManaged(false);
            cancelButton.setText("取消");

            scan();
        }

        static void show(ServerProfile profile, ServerManagerService service, PluginContext context,
                         JavaCompatibility.Verdict verdict, Consumer<ServerProfile> onFixed,
                         Runnable onProfileChanged) {
            Platform.runLater(() -> Controllers.dialog(
                    new JavaPickerDialog(profile, service, context, verdict, onFixed, onProfileChanged)));
        }

        /// Scans installed runtimes off the UI thread, then renders one row per candidate.
        private void scan() {
            Thread worker = new Thread(() -> {
                List<JavaScanner.JavaInfo> candidates =
                        JavaCompatibility.findUsableJavas(profile, service.getDataDirectory());
                Platform.runLater(() -> render(candidates));
            }, "server-manager-java-picker");
            worker.setDaemon(true);
            worker.start();
        }

        private void render(List<JavaScanner.JavaInfo> candidates) {
            list.getChildren().clear();
            if (candidates.isEmpty()) {
                hint.setText("没有找到可用的 Java（已安装的版本都低于 Java "
                        + verdict.getRequiredMajor() + "，或证书库过旧）。\n请使用“下载并安装”选项。");
                return;
            }
            hint.setText("以下 Java 可以运行这个服务端，选择一个即可切换：");
            for (JavaScanner.JavaInfo candidate : candidates) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                Label label = new Label(candidate.toString());
                HBox.setHgrow(label, Priority.ALWAYS);
                label.setWrapText(true);
                JFXButton use = new JFXButton("使用");
                use.getStyleClass().add("dialog-accept");
                use.setOnAction(e -> {
                    fireEvent(new DialogCloseEvent());
                    applyAndRestart(candidate.getPath());
                });
                row.getChildren().setAll(label, use);
                list.getChildren().add(row);
            }
        }

        private void applyAndRestart(String executable) {
            profile.setJavaPath(executable);
            try {
                service.updateProfile(profile);
            } catch (Exception e) {
                String message = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                context.logError("保存 Java 设置失败", e);
                Controllers.dialog(message, "保存失败", MessageDialogPane.MessageType.ERROR);
                return;
            }
            context.logInfo("服务端「" + profile.getName() + "」的 Java 已切换为 " + executable);
            onProfileChanged.run();
            Controllers.dialog("已把服务端「" + profile.getName() + "」的 Java 切换为：\n" + executable
                            + "\n\n现在重新启动服务端。",
                    "Java 已切换", MessageDialogPane.MessageType.SUCCESS,
                    () -> onFixed.accept(profile));
        }
    }
}
