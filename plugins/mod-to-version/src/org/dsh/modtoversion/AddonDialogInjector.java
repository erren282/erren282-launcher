package org.dsh.modtoversion;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;

import java.util.Optional;

/**
 * Adds the 「下载到对应版本」 button to one of the launcher's content download dialogs.
 *
 * <p>Separated from the watching plumbing so the decision — is this a download dialog,
 * which section is it, and what release does it show — can be tested without a scene.
 * If this stops recognising a dialog the button silently never appears, which is
 * exactly the kind of failure that is invisible without a test.</p>
 */
final class AddonDialogInjector {

    /// The button's label.
    static final String BUTTON_TEXT = "下载到对应版本";

    /// Receives what the dialog was showing.
    interface Handler {
        /// @param kind    the download section the dialog belongs to
        /// @param release the release name read from the dialog heading
        void onDownloadToVersion(AddonKind kind, String release);
    }

    private AddonDialogInjector() {
    }

    /// Adds the button to {@code node} when it is one of the supported download dialogs.
    ///
    /// @return true when the button was added; false for any other dialog
    static boolean tryInject(Node node, Handler handler) {
        if (!(node instanceof JFXDialogLayout layout)) {
            return false;
        }
        String heading = headingTextOf(layout);
        Optional<DialogHeader.Heading> parsedHeading = DialogHeader.parse(heading);
        if (parsedHeading.isEmpty()) {
            return false;
        }
        DialogHeader.Heading parsed = parsedHeading.get();
        String release = parsed.releaseNameOf(heading.trim());
        JFXButton button = new JFXButton(BUTTON_TEXT);
        button.getStyleClass().add("dialog-accept");
        button.setOnAction(event -> handler.onDownloadToVersion(parsed.kind(), release));
        insertBeforeCancel(layout.getActions(), button);
        return true;
    }

    /// Puts {@code button} just before the row's cancel button, so the launcher's own
    /// buttons keep their order and 取消 stays rightmost.
    ///
    /// <p>The action row is a {@code FlowPane} aligned to the right, so order <em>is</em>
    /// position: appending would push 取消 leftwards, which is not wanted. The cancel
    /// button is found by its {@code dialog-cancel} style class rather than by its
    /// label, so a localised launcher behaves the same. When there is no cancel button
    /// the new one is appended, which is the best available fallback.</p>
    static void insertBeforeCancel(ObservableList<Node> actions, JFXButton button) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).getStyleClass().contains("dialog-cancel")) {
                actions.add(i, button);
                return;
            }
        }
        actions.add(button);
    }

    /// The download section a dialog layout belongs to, for logging.
    ///
    /// @return the section, or empty when this is not one of the supported dialogs
    static Optional<AddonKind> describeKind(Node node) {
        if (!(node instanceof JFXDialogLayout layout)) {
            return Optional.empty();
        }
        return DialogHeader.kindOf(headingTextOf(layout));
    }

    /// The dialog's heading text.
    ///
    /// <p>The launcher builds the heading as {@code new HBox(new Label(i18n(...)))}, so
    /// the text lives one level down; this walks in to find the first label with
    /// text.</p>
    static String headingTextOf(JFXDialogLayout layout) {
        for (Node node : layout.getHeading()) {
            String text = textOf(node);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /// The first non-blank label text inside a node.
    private static String textOf(Node node) {
        if (node instanceof Labeled labeled
                && labeled.getText() != null && !labeled.getText().isBlank()) {
            return labeled.getText();
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String text = textOf(child);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
