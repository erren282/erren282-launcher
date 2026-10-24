package org.dsh.serverlist;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.Nullable;

/// 服务器列表页用到的一小组启动器风格控件构建方法（与「服务端管理」插件同款外观，
/// 但本插件独立打包，因此单独保留一份，不跨插件引用）。
final class ListUi {

    /// 图标尺寸，与「服务端管理」插件保持一致。
    private static final int ICON_SIZE = 40;

    private ListUi() {
    }

    /// A page heading with a subtitle line.
    public static VBox heading(String title, String subtitle) {
        return heading(title, subtitle, null);
    }

    /// A heading whose back arrow sits on the title line.
    ///
    /// <p>The arrow used to be its own row above the heading, which put two back arrows on
    /// screen at once: the module's 「返回」 and, directly under it, the page title's. One row
    /// is enough — the arrow belongs to the title it returns from — and it gives the list
    /// below a little more room.</p>
    ///
    /// @param title    the heading text
    /// @param subtitle the description under it
    /// @param back     the back action, or null for a heading with no arrow
    public static VBox heading(String title, String subtitle, @Nullable Runnable back) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label sub = new Label(subtitle);
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-mid-text-color;");
        sub.setWrapText(true);

        VBox text = new VBox(2, heading, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        Node titleLine;
        if (back == null) {
            titleLine = text;
        } else {
            Button arrow = new Button();
            arrow.setGraphic(org.jackhuang.hmcl.ui.SVG.ARROW_BACK.createIcon(
                    arrow.textFillProperty()));
            arrow.getStyleClass().add("dialog-accept");
            arrow.setFocusTraversable(false);
            arrow.setOnAction(e -> back.run());
            arrow.setTooltip(new Tooltip("返回"));
            // Centred against the title block so it reads as belonging to the title.
            HBox line = new HBox(8, arrow, text);
            line.setAlignment(Pos.CENTER_LEFT);
            titleLine = line;
        }

        VBox box = new VBox(2, titleLine);
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
    public static HBox entryRow(Node icon, String title, String detail, Region... actions) {
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
}
