package org.dsh.servermanager;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// A small dialog that hosts arbitrary form content.
///
/// <p>[org.jackhuang.hmcl.ui.construct.MessageDialogPane] only takes plain text, which is not
/// enough for the ban form (a reason field and a duration picker), so this wraps a content node
/// in the launcher's own dialog chrome — same look, same close behaviour.</p>
@NotNullByDefault
final class ManageDialog {

    private ManageDialog() {
    }

    /// Shows `content` in a dialog with a confirm and a cancel button.
    ///
    /// @param onConfirm runs when the confirm button is pressed, before the dialog closes
    static void show(String title, Node content, String confirmText, Runnable onConfirm) {
        show(title, content, confirmText, onConfirm, null);
    }

    /// Shows `content` in a dialog.
    ///
    /// @param onConfirm runs when the confirm button is pressed, before the dialog closes
    /// @param danger true to tint the confirm button as a destructive action
    static void show(String title, Node content, String confirmText, Runnable onConfirm,
                     @Nullable Boolean danger) {
        JFXDialogLayout layout = new JFXDialogLayout();

        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        layout.setHeading(heading);

        VBox body = new VBox(content);
        body.setPadding(new Insets(6, 0, 6, 0));
        layout.setBody(body);

        JFXButton cancel = new JFXButton("取消");
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));

        JFXButton confirm = FXUtils.newRaisedButton(confirmText);
        if (Boolean.TRUE.equals(danger))
            confirm.setStyle("-fx-text-fill: -monet-error;");
        confirm.setOnAction(e -> {
            layout.fireEvent(new DialogCloseEvent());
            // run after the dialog is gone, so any further dialog it opens is not stacked
            javafx.application.Platform.runLater(onConfirm);
        });

        layout.setActions(cancel, confirm);
        FXUtils.onEscPressed(layout, cancel::fire);
        Controllers.dialog(layout);
    }
}
