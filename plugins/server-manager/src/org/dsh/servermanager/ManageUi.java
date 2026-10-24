package org.dsh.servermanager;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/// Shared building blocks for the server management sub-pages.
///
/// <p>Both sub-pages are built from the same [card row][#entryRow]: a leading icon, a title with
/// status badges, a multi-line description, and action buttons on the right. One layout for both
/// pages means the two lists line up into a single column of equal-width cards instead of
/// reading as two mismatched sections.</p>
@NotNullByDefault
final public class ManageUi {

    /// Width the icon column reserves; shared by every row so text starts at the same x.
    static final double ICON_SIZE = 40;

    private ManageUi() {
    }

    /// A page heading with a subtitle line.
    public static VBox heading(String title, String subtitle) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label sub = new Label(subtitle);
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        sub.setWrapText(true);
        VBox box = new VBox(2, heading, sub);
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }

    /// One uniform card row: icon + text block + action buttons.
    ///
    /// <p>Both management pages use exactly this layout, which is what makes their lists read as
    /// one column: the icon always sits in the same place, text starts at the same x, and the
    /// buttons hug the right edge at their natural width (the text side shrinks instead, so a
    /// button label is never truncated to something like `下载到本服…`).</p>
    ///
    /// @param icon the leading icon, or null for no icon
    /// @param title the primary line (player / plugin name)
    /// @param detail the secondary lines; `\n` breaks are honoured
    /// @param actions the buttons, right-aligned in the given order
    public static HBox entryRow(@Nullable Node icon, String title, String detail, Region... actions) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label detailLabel = new Label(detail == null ? "" : detail);
        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        detailLabel.setWrapText(true);

        VBox text = new VBox(3, titleLabel, detailLabel);
        HBox.setHgrow(text, Priority.ALWAYS);

        // A FlowPane rather than an HBox: when the window is too narrow for every button, the
        // overflow wraps onto a second line instead of the labels being squeezed into ellipsis
        // (the old layout showed `封禁…` for a button that really reads `封禁玩家…`).
        //
        // prefWrapLength must be a concrete number: with USE_COMPUTED_SIZE the FlowPane's own
        // preferred width collapses and every button ends up on its own line.
        javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(8, 6);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        buttons.setRowValignment(javafx.geometry.VPos.CENTER);
        buttons.setPrefWrapLength(600);
        buttons.setMaxWidth(Region.USE_PREF_SIZE);
        for (Region action : actions) {
            action.setMinWidth(Region.USE_PREF_SIZE);
            buttons.getChildren().add(action);
        }

        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: -monet-surface-container;"
                + " -fx-background-radius: 6; -fx-border-color: -monet-surface-container-high;"
                + " -fx-border-radius: 6;");
        if (icon != null) {
            // reserve the icon column even before the image arrives, so text does not jump
            StackPane iconPane = new StackPane(icon);
            iconPane.setMinSize(ICON_SIZE, ICON_SIZE);
            iconPane.setPrefSize(ICON_SIZE, ICON_SIZE);
            iconPane.setMaxSize(ICON_SIZE, ICON_SIZE);
            row.getChildren().add(iconPane);
        }
        row.getChildren().addAll(text, buttons);
        return row;
    }

    /// An input row: a text field with buttons, in the same card style as [entryRow].
    public static HBox inputRow(String title, String detail, Region input, Region... actions) {
        input.setMaxWidth(280);
        input.setMinWidth(120);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label detailLabel = new Label(detail == null ? "" : detail);
        detailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        detailLabel.setWrapText(true);

        VBox text = new VBox(3, titleLabel, detailLabel);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox right = new HBox(8, input);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE);
        for (Region action : actions) {
            action.setMinWidth(Region.USE_PREF_SIZE);
            right.getChildren().add(action);
        }

        HBox row = new HBox(12, text, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: -monet-surface-container;"
                + " -fx-background-radius: 6; -fx-border-color: -monet-surface-container-high;"
                + " -fx-border-radius: 6;");
        return row;
    }

    /// A small neutral button (used for secondary actions).
    public static JFXButton button(String text, Runnable action) {
        JFXButton button = new JFXButton(text);
        button.setOnAction(e -> action.run());
        button.setStyle("-fx-padding: 4 12 4 12;");
        return button;
    }

    /// A prominent button for the primary action of a row.
    public static JFXButton primaryButton(String text, Runnable action) {
        JFXButton button = FXUtils.newRaisedButton(text);
        button.setOnAction(e -> action.run());
        return button;
    }

    /// A destructive button (delete / ban), tinted with the error colour.
    static JFXButton dangerButton(String text, Runnable action) {
        JFXButton button = new JFXButton(text);
        button.setOnAction(e -> action.run());
        button.setStyle("-fx-text-fill: -monet-error; -fx-padding: 4 12 4 12;");
        return button;
    }

    /// A read-only card, used for empty states and explanations.
    static HBox infoCard(String title, String detail) {
        return entryRow(null, title, detail);
    }

    /// Opens a URL in the system browser.
    static void openLink(String url, Consumer<String> opener) {
        opener.accept(url);
    }

    /// Scrolls every ancestor `ScrollPane` of `node` back to the top.
    ///
    /// <p>The launcher wraps plugin pages in one shell-wide scroll pane and keeps the page node
    /// (and therefore its scroll position) cached between visits, so returning to a page showed
    /// whatever part of it was last on screen instead of the top.</p>
    ///
    /// <p>The reset is deferred and repeated: `vvalue` only sticks once the content has been laid
    /// out, and a page that keeps loading (the plugin store fills its results asynchronously)
    /// grows *after* the first reset, which drags the thumb away from the top again. A few passes
    /// over the next second cover those late layout changes. The waits run on a daemon thread —
    /// the caller is usually on the JavaFX thread, and sleeping there would freeze the UI.</p>
    /// Scrolls the nearest ancestor ScrollPane so that `node` sits at the top of the
    /// viewport. Used after paging: the new results should be in view, not the page middle.
    /// The nearest ancestor ScrollPane of `node`, or null.
    public static javafx.scene.control.ScrollPane ancestorScroll(javafx.scene.Node node) {
        javafx.scene.Parent parent = node.getParent();
        while (parent != null) {
            if (parent instanceof javafx.scene.control.ScrollPane pane)
                return pane;
            parent = parent.getParent();
        }
        return null;
    }

    public static void scrollToNode(javafx.scene.Node node) {
        javafx.scene.Parent parent = node.getParent();
        while (parent != null) {
            if (parent instanceof javafx.scene.control.ScrollPane pane && pane.getContent() != null) {
                javafx.geometry.Bounds contentBounds =
                        pane.getContent().localToScene(pane.getContent().getBoundsInLocal());
                javafx.geometry.Bounds nodeBounds = node.localToScene(node.getBoundsInLocal());
                double viewport = pane.getViewportBounds().getHeight();
                double content = contentBounds.getHeight();
                if (content > viewport + 1 && nodeBounds != null) {
                    double y = nodeBounds.getMinY() - contentBounds.getMinY();
                    pane.setVvalue(Math.max(0, Math.min(1, y / (content - viewport))));
                }
                return;
            }
            parent = parent.getParent();
        }
    }

    public static void scrollToTop(javafx.scene.Node node) {
        // 把页面带回顶部只需要覆盖新内容的首次布局（两个脉冲，约几十毫秒）。
        // 旧实现用线程在 1.2 秒里强制复位 6 次——用户在那之后一开始滚动就被拽回去，
        // 看起来就是页面在“抽搐”。
        javafx.application.Platform.runLater(() -> resetAncestorScroll(node));
        javafx.application.Platform.runLater(() -> resetAncestorScroll(node));
        javafx.application.Platform.runLater(() -> resetAncestorScroll(node));
    }

    private static void resetAncestorScroll(javafx.scene.Node node) {
        javafx.scene.Parent parent = node.getParent();
        while (parent != null) {
            if (parent instanceof javafx.scene.control.ScrollPane pane) {
                pane.setVvalue(0);
                pane.setHvalue(0);
            }
            parent = parent.getParent();
        }
    }
}
