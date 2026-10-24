package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniRunning;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniWavingArms;

import java.nio.file.Path;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * 皮肤/披风切换主对话框：左侧 3D 预览，右侧提供
 * “在线（LittleSkin 皮肤库）”与“自定义（本地文件）”两种来源。
 */
public final class SkinCapeDialog extends StackPane {

    public enum Mode { SKIN, CAPE }

    private final Account account;
    private final Mode mode;
    private final Path dataDir;

    public SkinCapeDialog(Account account, Mode mode, Path dataDir) {
        this.account = account;
        this.mode = mode;
        this.dataDir = dataDir;

        JFXDialogLayout layout = new JFXDialogLayout();
        getChildren().setAll(layout);

        String title = mode == Mode.SKIN ? "切换皮肤" : "切换披风";
        layout.setHeading(new Label(title + " · " + account.getProfileName()));

        // ---- 左侧：3D 皮肤预览（账户皮肤变化时自动刷新） ----
        SkinCanvas canvas = new SkinCanvas(TexturesLoader.getDefaultSkinImage(), 260, 260, true);
        canvas.getAnimationPlayer().addSkinAnimation(
                new SkinAniWavingArms(100, 2000, 7.5, canvas),
                new SkinAniRunning(100, 100, 30, canvas));
        canvas.enableRotation(.5);
        StackPane canvasPane = new StackPane(canvas);
        canvasPane.setPrefSize(260, 260);

        ObservableValue<TexturesLoader.LoadedTexture> binding = TexturesLoader.skinBinding(account);
        javafx.beans.value.ChangeListener<TexturesLoader.LoadedTexture> listener = (obs, ov, nv) -> {
            if (nv != null)
                canvas.updateSkin(nv.image(), "slim".equals(nv.metadata().get("model")), null);
        };
        TexturesLoader.LoadedTexture current = binding.getValue();
        if (current != null)
            listener.changed(binding, null, current);
        binding.addListener(listener);

        // ---- 右侧：来源选择 ----
        VBox options = new VBox(12);
        options.setPrefWidth(240);

        if (mode == Mode.SKIN) {
            addOption(options, "在线皮肤（LittleSkin 皮肤库）",
                    () -> Controllers.dialog(new LittleSkinLibraryPane(
                            LittleSkinLibraryPane.Filter.SKIN, dataDir,
                            file -> SkinApplier.applySkin(account, file,
                                    this::closeAndRefresh, SkinCapeDialog::showError))));
            addOption(options, "自定义皮肤（选择本地文件）", () -> pickAndApply(true));
            if (account instanceof OfflineAccount)
                addOption(options, "跟随 LittleSkin 皮肤源（按用户名）",
                        () -> SkinApplier.applyLittleSkinSource((OfflineAccount) account,
                                this::closeAndRefresh, SkinCapeDialog::showError));
        } else {
            if (account instanceof MicrosoftAccount) {
                MicrosoftAccount microsoftAccount = (MicrosoftAccount) account;
                addOption(options, "选择已拥有的官方披风",
                        () -> Controllers.dialog(new MojangCapePane(microsoftAccount, this::closeAndRefresh)));
                Label hint = new Label("微软账户的披风由 Mojang 官方管理，\n只能切换账户已拥有的官方披风，\n不支持上传自定义披风。");
                hint.setWrapText(true);
                options.getChildren().add(hint);
            } else {
                addOption(options, "在线披风（LittleSkin 皮肤库）",
                        () -> Controllers.dialog(new LittleSkinLibraryPane(
                                LittleSkinLibraryPane.Filter.CAPE, dataDir,
                                file -> SkinApplier.applyCape(account, file,
                                        this::closeAndRefresh, SkinCapeDialog::showError))));
                addOption(options, "自定义披风（选择本地文件）", () -> pickAndApply(false));
                if (account instanceof OfflineAccount)
                    addOption(options, "跟随 LittleSkin 皮肤源（皮肤与披风均跟随）",
                            () -> SkinApplier.applyLittleSkinSource((OfflineAccount) account,
                                    this::closeAndRefresh, SkinCapeDialog::showError));
            }
        }

        HBox body = new HBox(20, canvasPane, options);
        body.setPadding(new Insets(8));
        VBox.setVgrow(options, Priority.NEVER);
        layout.setBody(body);

        JFXButton closeButton = new JFXButton(i18n("button.cancel"));
        closeButton.getStyleClass().add("dialog-cancel");
        closeButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        layout.setActions(closeButton);
    }

    private void addOption(VBox options, String text, Runnable action) {
        JFXButton button = FXUtils.newBorderButton(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> action.run());
        options.getChildren().add(button);
    }

    private void pickAndApply(boolean isSkin) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(isSkin ? "选择皮肤文件" : "选择披风文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("皮肤/披风图片", "*.png"));
        Path file = org.jackhuang.hmcl.util.io.FileUtils.toPath(chooser.showOpenDialog(Controllers.getStage()));
        if (file == null)
            return;
        if (isSkin)
            SkinApplier.applySkin(account, file, this::closeAndRefresh, SkinCapeDialog::showError);
        else
            SkinApplier.applyCape(account, file, this::closeAndRefresh, SkinCapeDialog::showError);
    }

    private void closeAndRefresh() {
        Controllers.showToast("已应用" + (mode == Mode.SKIN ? "皮肤" : "披风"));
        fireEvent(new DialogCloseEvent());
    }

    private static void showError(String message) {
        Controllers.dialog(message, "应用失败",
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR);
    }
}
