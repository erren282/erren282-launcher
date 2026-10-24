package org.dsh.modtoversion;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.util.List;
import java.util.function.Consumer;

/// Asks which installed game version an addon should be downloaded into.
///
/// <p>Only versions the release actually fits are listed — same game version, and for a
/// mod a matching loader — so the click cannot install something the game would refuse to
/// load. The wording follows the download section: a resource pack goes into
/// `resourcepacks`, a world is unpacked into `saves`, and the dialogs say so.</p>
///
/// <h2>Sizing: the launcher window can be smaller than this dialog wants to be</h2>
/// <p>The launcher enforces a minimum content size of about 800×492, which is not much.
/// A dialog whose rows are laid out directly in an unbounded `VBox` collapses when there
/// are many versions: measured with twelve installed versions in a minimum window, the
/// dialog came out 452px tall against 492px of window, and its action row was squeezed
/// until the 取消 button had <em>zero height</em> — the dialog could not be dismissed at
/// all, which is exactly what a user reported.</p>
///
/// <p>So the list is capped in <em>height</em>, not in item count: it gets its own scroll
/// pane whose maximum height is derived from the window, the dialog's width follows the
/// window within sane bounds, and only the list flexes. The heading and the action row
/// therefore keep their space however small the window is, which is what keeps the dialog
/// operable.</p>
final class TargetVersionDialog extends JFXDialogLayout implements DialogAware {

    /// The narrowest the dialog may become, so the text still wraps sensibly.
    static final double MIN_DIALOG_WIDTH = 380;

    /// The widest the dialog may become, so it stays readable on a large screen.
    static final double MAX_DIALOG_WIDTH = 640;

    /// The share of the window the dialog may occupy, matching the launcher's own
    /// download dialogs.
    static final double WINDOW_SHARE = 0.7;

    /// Room kept for the heading, the hint and the action row, in pixels. The list gets
    /// whatever is left of the window.
    static final double CHROME_HEIGHT = 190;

    /// The list's height floor: enough to show two rows and make the scrolling obvious.
    static final double MIN_LIST_HEIGHT = 96;

    /// The height of the window this dialog is being fitted into, or a non-positive value
    /// when it is unknown.
    private final double windowHeight;

    /// Builds the dialog for a window of the given height.
    ///
    /// <p>The height is a parameter rather than a lookup so the layout can be exercised at
    /// the launcher's minimum size without a real window; production passes
    /// [TargetVersionDialog#currentWindowHeight].</p>
    ///
    /// @param windowHeight the window's height, or a non-positive value when unknown
    TargetVersionDialog(AddonKind kind, RemoteAddon.Version release,
                        List<VersionMatcher.Installed> targets,
                        Consumer<VersionMatcher.Installed> onPicked,
                        double windowHeight) {
        this.windowHeight = windowHeight;
        setHeading(new Label("选择要下载到哪个版本"));
        setBody(buildBody(kind, release, targets, onPicked));
        setActions(buildActions());
        applyWindowBounds();
    }

    /// Constrains the dialog width to the window, however small it is.
    ///
    /// <p>The width tracks the window between [MIN_DIALOG_WIDTH] and
    /// [MAX_DIALOG_WIDTH] instead of being fixed, so the dialog cannot be wider than the
    /// window it is shown in. Nothing sets a preferred height for the dialog as a whole —
    /// only the list inside it is capped — so the action row is never squeezed out.</p>
    private void applyWindowBounds() {
        double width = MIN_DIALOG_WIDTH;
        try {
            var stage = Controllers.getStage();
            if (stage != null) {
                width = clamp(stage.getWidth() * WINDOW_SHARE, MIN_DIALOG_WIDTH, MAX_DIALOG_WIDTH);
                // A shrink of the window while the dialog is open must not clip it either.
                stage.widthProperty().addListener((observable, oldValue, newValue) ->
                        setPrefWidth(clamp(newValue.doubleValue() * WINDOW_SHARE,
                                MIN_DIALOG_WIDTH, MAX_DIALOG_WIDTH)));
            }
        } catch (Throwable ignored) {
            // No window (a test, or very early startup): the minimum is a safe default.
        }
        setPrefWidth(width);
    }

    /// How tall the scrollable version list may be inside a window of the given height.
    ///
    /// <p>Derived from the window rather than fixed, so a short window yields a short list
    /// instead of a collapsed dialog. Never below [MIN_LIST_HEIGHT], because a list shorter
    /// than a row is not usable.</p>
    ///
    /// @param windowHeight the height of the launcher window, or a non-positive value when
    ///                     it is unknown
    /// @return the maximum height for the version list, in pixels
    static double maxListHeight(double windowHeight) {
        if (windowHeight <= 0 || Double.isNaN(windowHeight)) {
            return MIN_LIST_HEIGHT;
        }
        double available = windowHeight * WINDOW_SHARE - CHROME_HEIGHT;
        return Math.max(MIN_LIST_HEIGHT, available);
    }

    /// Restricts a value to a range.
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /// The height of the launcher window, or -1 when it cannot be read.
    private static double currentWindowHeight() {
        try {
            var stage = Controllers.getStage();
            return stage == null ? -1 : stage.getHeight();
        } catch (Throwable t) {
            return -1;
        }
    }

    /// Shows the picker.
    ///
    /// @param kind     the download section, deciding the wording
    /// @param release  the release being installed
    /// @param targets  the installed versions that can take it
    /// @param onPicked receives the chosen version
    static void show(AddonKind kind, RemoteAddon.Version release,
                     List<VersionMatcher.Installed> targets,
                     Consumer<VersionMatcher.Installed> onPicked) {
        Controllers.dialog(new TargetVersionDialog(kind, release, targets, onPicked,
                currentWindowHeight()));
    }

    /// Explains that nothing installed can take this release.
    static void showNothingFits(AddonKind kind, RemoteAddon.Version release) {
        StringBuilder message = new StringBuilder();
        message.append("没有已安装的游戏版本符合这个").append(kind.noun()).append("的要求。");
        if (release != null) {
            if (release.gameVersions() != null && !release.gameVersions().isEmpty()) {
                message.append("\n\n它支持的游戏版本：")
                        .append(String.join("、", release.gameVersions()));
            }
            // Loaders are only a requirement for mods; mentioning them for a resource
            // pack would explain a constraint that was never applied.
            if (kind.loaderSensitive()) {
                if (release.loaders() != null && !release.loaders().isEmpty()) {
                    List<String> names = release.loaders().stream()
                            .map(VersionMatcher::nameOf).toList();
                    message.append("\n它需要的加载器：").append(String.join("、", names));
                }
            }
        }
        message.append("\n\n请先用启动器安装对应版本，或改用「下载到本地文件夹」。");
        Controllers.dialog(message.toString(), "没有可用的版本",
                MessageDialogPane.MessageType.WARNING);
    }

    private Node buildBody(AddonKind kind, RemoteAddon.Version release,
                           List<VersionMatcher.Installed> targets,
                           Consumer<VersionMatcher.Installed> onPicked) {
        VBox body = new VBox(6);
        body.setPadding(new Insets(4, 0, 4, 0));

        Label hint = new Label("把「" + release.name() + "」下载到哪个版本的 "
                + kind.targetDescription() + "：");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 12px; -fx-opacity: 0.75;");

        VBox rows = new VBox(6);
        rows.setPadding(new Insets(0, 8, 0, 0));
        for (VersionMatcher.Installed target : targets) {
            rows.getChildren().add(buildRow(target, onPicked));
        }

        // The list scrolls; the heading, the hint and the buttons do not. This is the
        // whole point: however many versions there are, the dialog keeps fitting in the
        // window and the 取消 button stays clickable.
        double cap = maxListHeight(windowHeight);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(MIN_LIST_HEIGHT);
        scroll.setPrefHeight(cap);
        scroll.setMaxHeight(cap);
        // Deliberately no FXUtils.smoothScrolling here: it reaches into the launcher's
        // settings through AnimationUtils, whose static initialiser fails when the
        // settings are not loaded, and a failed class initialiser poisons every later use
        // of it — the dialog would then throw NoClassDefFoundError on the JavaFX thread.
        // Smooth scrolling is cosmetic; a dialog that always opens is not.
        VBox.setVgrow(scroll, Priority.ALWAYS);

        body.getChildren().addAll(hint, scroll);
        return body;
    }

    /// One selectable installed version.
    private Node buildRow(VersionMatcher.Installed target,
                          Consumer<VersionMatcher.Installed> onPicked) {
        AdvancedListItem row = new AdvancedListItem();
        row.getStyleClass().add("navigation-drawer-item");
        row.setTitle(target.describe());
        row.setSubtitle(target.subtitle());
        // The version's own icon, as the launcher's version list shows it.
        Image icon = ModToVersionEngine.versionIcon(target.id());
        if (icon != null) {
            ImageView view = new ImageView(icon);
            view.setFitWidth(AdvancedListItem.LEFT_ICON_SIZE);
            view.setFitHeight(AdvancedListItem.LEFT_ICON_SIZE);
            view.setPreserveRatio(true);
            AdvancedListItem.setAlignment(view, Pos.CENTER);
            AdvancedListItem.setMargin(view, AdvancedListItem.LEFT_ICON_MARGIN);
            row.setLeftGraphic(view);
        } else {
            row.setLeftIcon(SVG.GAMEPAD);
        }
        row.setOnAction(event -> {
            fireEvent(new DialogCloseEvent());
            onPicked.accept(target);
        });
        return row;
    }

    private Node buildActions() {
        JFXButton cancel = new JFXButton("取消");
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        return new HBox(8, cancel);
    }
}
