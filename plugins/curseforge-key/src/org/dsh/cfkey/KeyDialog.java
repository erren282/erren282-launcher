package org.dsh.cfkey;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXPasswordField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.util.function.Consumer;

/**
 * Asks the user for a CurseForge API key and verifies it before accepting it.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>the input is a password field, so a key pasted while someone is watching is not
 *       displayed in full — and, more importantly, so it is not left visible on screen;</li>
 *   <li>the dialog explains where to get a key, because "paste your API key" without that
 *       is a dead end for most people;</li>
 *   <li>verification happens on a background thread with the buttons disabled, so the
 *       window does not freeze while CurseForge is contacted;</li>
 *   <li>when CurseForge cannot be reached the user may still save the key — being offline
 *       now is not evidence the key is wrong, and refusing would make the plugin unusable
 *       on a plane.</li>
 * </ul>
 */
final class KeyDialog extends JFXDialogLayout implements DialogAware {

    /// Where a key can be obtained.
    static final String REGISTRATION_URL = "https://console.curseforge.com/";

    private final JFXPasswordField field = new JFXPasswordField();
    private final Label status = new Label();
    private final JFXButton saveButton = new JFXButton("保存并启用");
    private final JFXButton cancelButton = new JFXButton("取消");
    private final Consumer<String> onAccepted;

    private KeyDialog(String initialValue, Consumer<String> onAccepted) {
        this.onAccepted = onAccepted;

        setHeading(new Label("填写 CurseForge API Key"));
        setBody(buildBody());
        setActions(buildActions());

        if (initialValue != null && !initialValue.isEmpty()) {
            field.setText(initialValue);
        }
        setPrefWidth(520);
    }

    /// Shows the dialog.
    ///
    /// @param initialValue a key to pre-fill, or null
    /// @param onAccepted   receives a verified (or explicitly accepted) key
    static void show(String initialValue, Consumer<String> onAccepted) {
        Controllers.dialog(new KeyDialog(initialValue, onAccepted));
    }

    private VBox buildBody() {
        VBox body = new VBox(10);
        body.setPadding(new Insets(8, 0, 4, 0));

        Label explain = new Label(
                "CurseForge 的下载源需要一枚 API Key 才能使用。只需填写一次，之后会自动保存。");
        explain.setWrapText(true);
        explain.setStyle("-fx-font-size: 12px; -fx-opacity: 0.8;");

        Hyperlink register = new Hyperlink("到 CurseForge 控制台申请一枚 Key");
        register.setOnAction(event -> {
            try {
                FXUtils.openLink(REGISTRATION_URL);
            } catch (Throwable t) {
                Controllers.dialog("无法打开浏览器，请手动访问：\n" + REGISTRATION_URL);
            }
        });

        field.setPromptText("粘贴 API Key");
        field.setStyle("-fx-prompt-text-fill: derive(-fx-control-inner-background, -30%);");
        VBox.setVgrow(field, Priority.NEVER);

        status.setWrapText(true);
        status.setStyle("-fx-font-size: 12px;");

        body.getChildren().addAll(explain, register, field, status);
        return body;
    }

    private HBox buildActions() {
        saveButton.getStyleClass().add("dialog-accept");
        saveButton.setOnAction(event -> submit());

        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(event -> fireEvent(new DialogCloseEvent()));

        HBox actions = new HBox(8, saveButton, cancelButton);
        return actions;
    }

    /// Verifies what was entered, then either accepts it or explains why not.
    private void submit() {
        String key = field.getText() == null ? "" : field.getText().trim();
        if (key.isEmpty()) {
            setStatus("请输入 API Key。", true);
            return;
        }

        setBusy(true);
        setStatus("正在验证…", false);

        Thread worker = new Thread(() -> {
            KeyVerifier.Outcome outcome = KeyVerifier.verify(key);
            Platform.runLater(() -> onVerified(key, outcome));
        }, "curseforge-key-verify");
        worker.setDaemon(true);
        worker.start();
    }

    /// Handles the verification result on the JavaFX thread.
    private void onVerified(String key, KeyVerifier.Outcome outcome) {
        setBusy(false);
        switch (outcome.verdict()) {
            case VALID -> {
                fireEvent(new DialogCloseEvent());
                onAccepted.accept(key);
            }
            case REJECTED -> setStatus(outcome.detail().isEmpty()
                    ? "这个 Key 无效。" : outcome.detail(), true);
            case UNREACHABLE -> {
                // Offline is not the key's fault, so offer to keep it anyway.
                setStatus(outcome.detail() + "。可以稍后重试，或仍然保存这个 Key。", false);
                saveButton.setText("仍然保存");
                saveButton.setOnAction(event -> {
                    fireEvent(new DialogCloseEvent());
                    onAccepted.accept(key);
                });
            }
        }
    }

    /// Disables the controls while a verification is in flight.
    private void setBusy(boolean busy) {
        saveButton.setDisable(busy);
        cancelButton.setDisable(false);
        field.setDisable(busy);
    }

    /// Shows a line of feedback under the field.
    ///
    /// @param text    the message
    /// @param problem true when it describes a problem, which is shown in the error colour
    private void setStatus(String text, boolean problem) {
        status.setText(text);
        status.setStyle("-fx-font-size: 12px;"
                + (problem ? " -fx-text-fill: #d32f2f;" : " -fx-opacity: 0.75;"));
    }
}
