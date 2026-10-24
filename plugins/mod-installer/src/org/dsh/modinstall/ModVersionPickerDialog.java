package org.dsh.modinstall;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Asks which version of a mod to install, when the user chose to update.
 *
 * <p>Offers the two newest test builds and the newest stable build that fit the
 * selected game version and the mod's loader — the three the specification asks
 * for. Choosing one applies it and returns to the install dialog; cancelling
 * simply returns there, leaving the user exactly where they were.</p>
 */
final class ModVersionPickerDialog extends JFXDialogLayout implements DialogAware {

    /// Modrinth's publish dates, rendered in the user's own time zone.
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Consumer<RemoteAddon.Version> onPicked;

    private ModVersionPickerDialog(ModJarInfo.Info mod, List<RemoteAddon.Version> candidates,
                                   Consumer<RemoteAddon.Version> onPicked,
                                   Runnable onCancelled) {
        this.onPicked = onPicked;
        setHeading(new Label("选择要安装的版本"));
        setBody(buildBody(mod, candidates));
        setActions(buildActions(onCancelled));
        setPrefWidth(560);
    }

    /**
     * Shows the picker.
     *
     * @param mod           the dropped mod being updated
     * @param candidates    what to offer
     * @param onPicked      receives the chosen version
     * @param onCancelled   run when the user backs out, to restore the install dialog
     */
    static void show(ModJarInfo.Info mod, List<RemoteAddon.Version> candidates,
                     Consumer<RemoteAddon.Version> onPicked, Runnable onCancelled) {
        if (candidates.isEmpty()) {
            // Plain notice; there is nothing to choose between.
            ModInstallDialog.notify("找不到可更新的版本",
                    "没有找到「" + mod.name() + "」适配当前游戏版本与加载器的可用版本，"
                            + "可能该 Mod 未发布到 Modrinth，或该版本尚未发布。");
            if (onCancelled != null) {
                onCancelled.run();
            }
            return;
        }
        Controllers.dialog(new ModVersionPickerDialog(mod, candidates, onPicked, onCancelled));
    }

    private Node buildBody(ModJarInfo.Info mod, List<RemoteAddon.Version> candidates) {
        VBox body = new VBox(8);
        body.setPadding(new Insets(4, 0, 4, 0));
        body.setPrefWidth(500);

        Label hint = new Label("为「" + mod.name() + "」选择一个要安装的版本：");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 12px; -fx-opacity: 0.75;");
        body.getChildren().add(hint);

        for (RemoteAddon.Version candidate : candidates) {
            AdvancedListItem row = new AdvancedListItem();
            row.getStyleClass().add("navigation-drawer-item");
            row.setTitle(candidate.version() + "  ·  "
                    + ModInstallerEngine.versionTypeLabel(candidate.versionType()));
            row.setSubtitle(describe(candidate));
            row.setOnAction(event -> {
                fireEvent(new DialogCloseEvent());
                if (onPicked != null) {
                    onPicked.accept(candidate);
                }
            });
            body.getChildren().add(row);
        }
        return body;
    }

    /** A candidate's second line: which game versions it covers, and when. */
    private static String describe(RemoteAddon.Version version) {
        StringBuilder text = new StringBuilder();
        if (version.gameVersions() != null && !version.gameVersions().isEmpty()) {
            text.append(String.join("、", version.gameVersions()));
        }
        if (version.datePublished() != null) {
            if (text.length() > 0) {
                text.append("  ·  ");
            }
            text.append(DATE.format(version.datePublished()));
        }
        return text.length() == 0 ? "未提供信息" : text.toString();
    }

    private Node buildActions(Runnable onCancelled) {
        JFXButton cancel = new JFXButton("取消");
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> {
            fireEvent(new DialogCloseEvent());
            // Back to the install dialog, unchanged.
            if (onCancelled != null) {
                onCancelled.run();
            }
        });
        return new HBox(8, cancel);
    }
}
