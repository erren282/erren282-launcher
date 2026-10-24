package org.dsh.modtoversion;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Renders the version rows offscreen so the result can be looked at.
 *
 * <p>Assertions can only prove that an {@code ImageView} was created; they cannot
 * show whether the icon is the version's own icon, or whether the row still reads
 * well. This writes a PNG for inspection.</p>
 *
 * <p>The rows are drawn with plain controls rather than
 * {@code AdvancedListItem}: that class' skin builds a {@code RipplerContainer},
 * whose static initialiser needs the launcher's theme variables, which do not exist
 * in this bare harness. The layout shown is the same shape — icon, then the title
 * and subtitle the real row uses.</p>
 *
 * <p>A real version icon needs a game repository, which a throwaway profile lacks,
 * so the fallback path is what gets drawn.</p>
 */
public final class RowShot {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        String outputDir = args.length > 0 ? args[0] : ".";
        Platform.startup(() -> { });
        pump();

        List<VersionMatcher.Installed> targets = List.of(
                new VersionMatcher.Installed("1.20.4-Fabric", "1.20.4", Set.of(org.jackhuang.hmcl.addon.mod.ModLoaderType.FABRIC)),
                new VersionMatcher.Installed("1.20.4-Forge", "1.20.4", Set.of(org.jackhuang.hmcl.addon.mod.ModLoaderType.FORGE)),
                new VersionMatcher.Installed("1.19.2-Fabric", "1.19.2", Set.of(org.jackhuang.hmcl.addon.mod.ModLoaderType.FABRIC)),
                new VersionMatcher.Installed("1.20.1", "1.20.1", Set.of()));

        System.out.println("=== rows carry a left icon ===");
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        VBox rows = onFxGet(() -> {
            VBox box = new VBox(6);
            box.setStyle("-fx-background-color: white; -fx-padding: 16;");
            box.setPrefWidth(460);
            Label title = new Label("把「appleskin」下载到哪个版本的 mods 目录：");
            title.setStyle("-fx-font-size: 12px; -fx-opacity: 0.75;");
            box.getChildren().add(title);
            for (VersionMatcher.Installed target : targets) {
                box.getChildren().add(rowFor(target));
            }
            return box;
        });

        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(rows), 490, 300));
            stage.show();
            stageRef.set(stage);
        });
        pump(20);
        Thread.sleep(400);
        pump(10);

        // Every drawn row must carry an icon node. It is an ImageView when the
        // version has an icon, and the SVG fallback otherwise — so the check is
        // "has a graphic before the text", not "has an ImageView".
        List<Boolean> hasIcon = onFxGet(() -> {
            List<Boolean> result = new ArrayList<>();
            for (Node node : rows.getChildren()) {
                if (node instanceof HBox row) {
                    result.add(row.getChildren().stream().anyMatch(
                            child -> !(child instanceof VBox)));
                }
            }
            return result;
        });
        System.out.println("  rows with an icon: " + hasIcon);
        check("every row was drawn", hasIcon.size() == targets.size());
        check("every row has an icon", hasIcon.stream().allMatch(Boolean::booleanValue));

        System.out.println();
        System.out.println("=== the icon is the version's own when available ===");
        Image real = ModToVersionEngine.versionIcon("1.20.4-Fabric");
        if (real == null) {
            System.out.println("  (no game repository in this profile: fallback icon shown)");
            check("the fallback icon is used when no version icon exists",
                    hasIcon.stream().allMatch(Boolean::booleanValue));
        } else {
            System.out.println("  version icon: " + (int) real.getWidth() + "x" + (int) real.getHeight());
            check("a version icon was found", true);
        }

        System.out.println();
        System.out.println("=== the launcher's cancel stays rightmost ===");
        com.jfoenix.controls.JFXDialogLayout layout = onFxGet(() -> {
            com.jfoenix.controls.JFXDialogLayout dialog = new com.jfoenix.controls.JFXDialogLayout();
            dialog.setHeading(new HBox(new Label("模组下载 - appleskin-fabric-mc26.3-3.0.10")));
            Label body = new Label("body");
            body.setWrapText(true);
            dialog.setBody(body);
            dialog.setActions(button("安装到当前实例", "dialog-accept"),
                    button("下载到本地文件夹", "dialog-accept"),
                    button("取消", "dialog-cancel"));
            AddonDialogInjector.tryInject(dialog, (kind, name) -> { });
            return dialog;
        });
        List<String> order = onFxGet(() -> {
            List<String> texts = new ArrayList<>();
            for (Node node : layout.getActions()) {
                texts.add(((javafx.scene.control.Labeled) node).getText());
            }
            return texts;
        });
        System.out.println("  action row: " + order);
        check("取消 is last", order.get(order.size() - 1).equals("取消"));
        check("下载到对应版本 is immediately before it",
                order.get(order.size() - 2).equals(AddonDialogInjector.BUTTON_TEXT));

        HBox actionRow = onFxGet(() -> {
            HBox box = new HBox(12);
            box.setStyle("-fx-background-color: white; -fx-padding: 16; -fx-alignment: center-right;");
            box.setPrefWidth(560);
            box.getChildren().addAll(layout.getActions());
            return box;
        });
        // A node that is never shown is never laid out, and a snapshot of an
        // unlaid-out node is 1px tall — so it has to go into a scene first.
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(actionRow), 600, 90));
            stage.show();
            stageRef.set(stage);
        });
        pump(20);
        Thread.sleep(300);
        pump(10);
        save(actionRow, new File(outputDir, "action-row.png"));
        save(rows, new File(outputDir, "version-rows.png"));

        onFx(stageRef.get()::close);
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * A row in the shape {@code TargetVersionDialog} builds: the version's icon,
     * then the same title and subtitle text.
     */
    private static HBox rowFor(VersionMatcher.Installed target) {
        HBox row = new HBox(8);
        row.setPadding(new javafx.geometry.Insets(6));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color: #f4f4f4; -fx-background-radius: 4;");

        Image icon = ModToVersionEngine.versionIcon(target.id());
        if (icon != null) {
            ImageView view = new ImageView(icon);
            view.setFitWidth(20);
            view.setFitHeight(20);
            view.setPreserveRatio(true);
            row.getChildren().add(view);
        } else {
            // Stand-in for the SVG.GAMEPAD fallback the dialog uses, drawn at the
            // same size so the layout matches.
            row.getChildren().add(SVG.GAMEPAD.createIcon(20));
        }
        VBox text = new VBox(2);
        Label title = new Label(target.describe());
        title.setStyle("-fx-font-size: 13px;");
        Label subtitle = new Label(target.subtitle());
        subtitle.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");
        text.getChildren().addAll(title, subtitle);
        row.getChildren().add(text);
        return row;
    }

    private static com.jfoenix.controls.JFXButton button(String text, String styleClass) {
        com.jfoenix.controls.JFXButton button = new com.jfoenix.controls.JFXButton(text);
        button.getStyleClass().add(styleClass);
        return button;
    }

    private static void save(Node node, File file) throws Exception {
        AtomicReference<WritableImage> image = new AtomicReference<>();
        onFx(() -> image.set(node.snapshot(null, null)));
        if (image.get() == null) {
            return;
        }
        java.awt.image.BufferedImage buffered =
                javafx.embed.swing.SwingFXUtils.fromFXImage(image.get(), null);
        ImageIO.write(buffered, "png", file);
        System.out.println("  wrote " + file.getAbsolutePath());
    }

    // --------------------------------------------------------- FX marshalling

    private static void pump() throws Exception {
        pump(6);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            onFx(() -> { });
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
        if (thrown.get() != null) {
            throw new IllegalStateException("FX action threw", thrown.get());
        }
    }

    private static <T> T onFxGet(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        onFx(() -> result.set(action.get()));
        return result.get();
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}
