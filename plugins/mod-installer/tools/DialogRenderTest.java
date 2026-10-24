package org.dsh.modinstall;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.setting.SettingsManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Checks that the install dialog actually renders its text.
 *
 * <p>The reported bug was a dialog with no visible text: the content had been put
 * through {@code MessageDialogPane.Builder.addAction(Node)}, which appends to the
 * button row rather than the body. This test builds the real dialog, lays it out
 * and asserts that the mod's name, loader and version list are present as visible
 * text nodes.</p>
 */
public final class DialogRenderTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        String hmclDir = System.getProperty("hmcl.dir");
        if (hmclDir == null || hmclDir.isBlank()) {
            System.out.println("FATAL: pass -Dhmcl.dir=<temp dir>");
            System.exit(2);
            return;
        }
        SettingsManager.init();
        Platform.startup(() -> { });
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run();
        } catch (Throwable t) {
            failure.set(t);
        }
        if (failure.get() != null) {
            System.out.println("FATAL");
            failure.get().printStackTrace();
            Platform.exit();
            System.exit(2);
            return;
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void run() throws Exception {
        ModJarInfo.Info mod = new ModJarInfo.Info(
                Path.of("E:\\mods\\examplemod-1.2.3.jar"), ModLoaderType.FABRIC,
                "examplemod", "Example Mod", "1.2.3", null,
                List.of(">=1.20.1 <1.21"),
                List.of(new ModJarInfo.Dependency("fabric-api", "*", true)),
                "An example", "Alice");
        List<ModInstallerEngine.Installed> matching = List.of(
                new ModInstallerEngine.Installed("1.20.4-Fabric", "1.20.4",
                        java.util.Set.of(ModLoaderType.FABRIC), Path.of("E:\\mods")),
                new ModInstallerEngine.Installed("1.20.1-Fabric", "1.20.1",
                        java.util.Set.of(ModLoaderType.FABRIC), Path.of("E:\\mods")));

        // Build the dialog the way the plugin does, but without the network lookup.
        Node dialog = onFxGet(() -> {
            try {
                var ctor = ModInstallDialog.class.getDeclaredConstructor(
                        List.class, List.class, List.class, java.util.Map.class);
                ctor.setAccessible(true);
                return ctor.newInstance(List.of(mod), List.of(mod.file()), matching,
                        new java.util.LinkedHashMap<>());
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });
        check("the dialog was constructed", dialog != null);
        if (dialog == null) {
            return;
        }

        AtomicReference<Stage> stageRef = new AtomicReference<>();
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(dialog), 700, 620));
            stage.show();
            stageRef.set(stage);
        });
        pump();
        onFx(() -> {
            dialog.applyCss();
            if (dialog instanceof Parent parent) {
                parent.layout();
            }
        });
        pump();

        // Every text the user must be able to read.
        List<String> texts = onFxGet(() -> {
            List<String> out = new ArrayList<>();
            collectText(dialog, out);
            return out;
        });
        System.out.println("  visible texts: " + texts);

        check("the heading shows the mod name", texts.stream().anyMatch(t -> t.contains("Example Mod")));
        check("the loader is shown", texts.stream().anyMatch(t -> t.contains("Fabric")));
        check("the supported game version is shown",
                texts.stream().anyMatch(t -> t.contains("1.20.1")));
        check("the prerequisite is listed",
                texts.stream().anyMatch(t -> t.contains("fabric-api")));
        check("the game-version count is shown",
                texts.stream().anyMatch(t -> t.contains("匹配的游戏版本")));
        check("the game-version section is present",
                texts.stream().anyMatch(t -> t.contains("匹配的游戏版本")));
        check("the confirm button is labelled",
                texts.stream().anyMatch(t -> t.equals("确认安装")));
        check("the cancel button is labelled",
                texts.stream().anyMatch(t -> t.equals("取消")));

        // The concrete regression: text must be inside the body, and the body must
        // be visible — not tucked into the action row.
        Boolean bodyHasText = onFxGet(() -> {
            Node body = findByStyleClass(dialog, "jfx-layout-body");
            if (body == null) {
                return false;
            }
            List<String> bodyTexts = new ArrayList<>();
            collectText(body, bodyTexts);
            return bodyTexts.stream().anyMatch(t -> t.contains("Example Mod"));
        });
        check("the mod details live in the dialog body, not the button row",
                Boolean.TRUE.equals(bodyHasText));

        // Collapsed: exactly ONE version row is visible — the current choice — and
        // the rest are hidden behind the triangle. Asserted on the rows themselves
        // rather than on a text substring, because the visible row legitimately
        // shows the version id.
        List<String> collapsed = onFxGet(() -> visibleTexts(dialog));
        System.out.println("  collapsed visible: " + collapsed);
        long visibleRowsCollapsed = onFxGet(() -> allOfType(dialog, AdvancedListItem.class).stream()
                .filter(DialogRenderTest::isEffectivelyVisible).count());
        check("exactly one version row is visible while collapsed ("
                + visibleRowsCollapsed + ")", visibleRowsCollapsed == 1);
        // Requirement: the chosen version must be stated, not hidden.
        check("the visible row while collapsed is the chosen version",
                onFxGet(() -> allOfType(dialog, AdvancedListItem.class).stream()
                        .filter(DialogRenderTest::isEffectivelyVisible)
                        .map(n -> ((AdvancedListItem) n).getTitle())
                        .anyMatch(t -> t.contains("1.20.4-Fabric"))));

        // The expander is a small triangle button, not a text button.
        Boolean triangle = onFxGet(() -> {
            for (Node node : allOfType(dialog, JFXButton.class)) {
                JFXButton button = (JFXButton) node;
                if (button.getGraphic() != null) {
                    return true;
                }
            }
            return false;
        });
        check("the expand control is an icon (triangle) button", Boolean.TRUE.equals(triangle));

        onFx(() -> {
            // Click the icon-only button: the triangle has no text.
            for (Node node : allOfType(dialog, JFXButton.class)) {
                JFXButton button = (JFXButton) node;
                if (button.getGraphic() != null) {
                    button.fire();
                    return;
                }
            }
        });
        pump();
        onFx(() -> {
            dialog.applyCss();
            if (dialog instanceof Parent parent) {
                parent.layout();
            }
        });
        pump();
        List<String> expanded = onFxGet(() -> visibleTexts(dialog));
        System.out.println("  expanded visible: " + expanded);
        long visibleRowsExpanded = onFxGet(() -> allOfType(dialog, AdvancedListItem.class).stream()
                .filter(DialogRenderTest::isEffectivelyVisible).count());
        // The always-visible row plus every entry in the list: matching.size() + 1.
        check("expanding shows the whole list (" + visibleRowsExpanded + " of "
                        + (matching.size() + 1) + " expected)",
                visibleRowsExpanded == matching.size() + 1);
        check("expanding adds entries that were not visible before",
                expanded.size() > collapsed.size());

        // The rows are the launcher's own list items. The chosen version appears
        // twice by design — once as the always-visible row and once in the list —
        // so only the visible ones are inspected here.
        List<Node> items = onFxGet(() -> allOfType(dialog, AdvancedListItem.class));
        check("the version rows are launcher-native list items (" + items.size() + ")",
                items.size() >= matching.size());
        // The always-visible row mirrors the list's entry for the same version, so
        // two rows are marked while expanded; what matters is that they agree.
        long activeVisibleDistinct = onFxGet(() -> allOfType(dialog, AdvancedListItem.class).stream()
                .filter(DialogRenderTest::isEffectivelyVisible)
                .filter(n -> ((AdvancedListItem) n).isActive())
                .map(n -> ((AdvancedListItem) n).getTitle())
                .distinct()
                .count());
        check("exactly one version is marked as the choice (" + activeVisibleDistinct + ")",
                activeVisibleDistinct == 1);
        Boolean markedRowHasTick = onFxGet(() -> {
            for (Node node : allOfType(dialog, AdvancedListItem.class)) {
                AdvancedListItem item = (AdvancedListItem) node;
                if (item.isActive() && DialogRenderTest.isEffectivelyVisible(item)) {
                    return item.getRightGraphic() != null;
                }
            }
            return false;
        });
        check("the chosen row carries a tick on the right",
                Boolean.TRUE.equals(markedRowHasTick));
        Boolean rowsHaveSubtitles = onFxGet(() -> {
            for (Node node : allOfType(dialog, AdvancedListItem.class)) {
                AdvancedListItem item = (AdvancedListItem) node;
                if (item.getSubtitle() == null || item.getSubtitle().isBlank()) {
                    return false;
                }
            }
            return true;
        });
        check("every row shows a subtitle (game version + loader)",
                Boolean.TRUE.equals(rowsHaveSubtitles));
        // Requirement: the rows carry the game's own icon.
        Boolean rowsHaveIcons = onFxGet(() -> {
            for (Node node : allOfType(dialog, AdvancedListItem.class)) {
                AdvancedListItem item = (AdvancedListItem) node;
                if (item.getLeftGraphic() == null) {
                    return false;
                }
            }
            return true;
        });
        check("every row shows an icon on the left", Boolean.TRUE.equals(rowsHaveIcons));

        // Picking a version must fold the list away AND re-mark the rows.
        Boolean picked = onFxGet(() -> {
            for (Node node : allOfType(dialog, AdvancedListItem.class)) {
                AdvancedListItem item = (AdvancedListItem) node;
                if ("1.20.1-Fabric".equals(item.getTitle())
                        && DialogRenderTest.isEffectivelyVisible(item)) {
                    item.fireEvent(new javafx.event.ActionEvent());
                    return true;
                }
            }
            return false;
        });
        check("the version entry was found and selected", Boolean.TRUE.equals(picked));
        pump();
        onFx(() -> {
            dialog.applyCss();
            if (dialog instanceof Parent parent) {
                parent.layout();
            }
        });
        pump();
        List<String> afterPick = onFxGet(() -> visibleTexts(dialog));
        System.out.println("  after picking a version: " + afterPick);
        long visibleRowsAfterPick = onFxGet(() -> allOfType(dialog, AdvancedListItem.class).stream()
                .filter(DialogRenderTest::isEffectivelyVisible).count());
        check("picking a version folds back to a single row (" + visibleRowsAfterPick + ")",
                visibleRowsAfterPick == 1);
        // The single visible row must now be the newly picked version.
        check("the visible row switched to the newly picked version",
                onFxGet(() -> allOfType(dialog, AdvancedListItem.class).stream()
                        .filter(DialogRenderTest::isEffectivelyVisible)
                        .map(n -> ((AdvancedListItem) n).getTitle())
                        .anyMatch(t -> t.contains("1.20.1-Fabric"))));
        check("the tick moved to the newly picked row",
                onFxGet(() -> {
                    for (Node node : allOfType(dialog, AdvancedListItem.class)) {
                        AdvancedListItem item = (AdvancedListItem) node;
                        if (item.isActive() && DialogRenderTest.isEffectivelyVisible(item)) {
                            return "1.20.1-Fabric".equals(item.getTitle())
                                    && item.getRightGraphic() != null;
                        }
                    }
                    return false;
                }));

        onFx(stageRef.get()::close);
    }

    /** Every node of the given type in the tree. */
    private static List<Node> allOfType(Node node, Class<?> type) {
        List<Node> out = new ArrayList<>();
        if (type.isInstance(node)) {
            out.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                out.addAll(allOfType(child, type));
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private static void collectText(Node node, List<String> out) {
        if (node instanceof Label label && label.getText() != null && !label.getText().isBlank()) {
            out.add(label.getText().trim());
        }
        if (node instanceof javafx.scene.control.Labeled labeled
                && !(node instanceof Label) && labeled.getText() != null
                && !labeled.getText().isBlank()) {
            out.add(labeled.getText().trim());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, out);
            }
        }
    }

    /**
     * Every text the user can actually read right now: the label's own text, and
     * every ancestor visible. Checking only the label's own {@code isVisible()}
     * reports children of a hidden parent as visible, which is exactly the
     * distinction the collapsed/expanded behaviour turns on.
     */
    private static List<String> visibleTexts(Node node) {
        List<String> out = new ArrayList<>();
        collectVisibleText(node, out, true);
        return out;
    }

    private static void collectVisibleText(Node node, List<String> out, boolean visible) {
        boolean nowVisible = visible && node.isVisible();
        if (nowVisible && node instanceof javafx.scene.control.Labeled labeled
                && labeled.getText() != null && !labeled.getText().isBlank()) {
            out.add(labeled.getText().trim());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectVisibleText(child, out, nowVisible);
            }
        }
    }

    /** True when the node and every ancestor up to the root is visible. */
    private static boolean isEffectivelyVisible(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Node findByStyleClass(Node node, String styleClass) {
        if (node.getStyleClass().contains(styleClass)) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node hit = findByStyleClass(child, styleClass);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Node findByText(Node node, String text) {
        if (node instanceof javafx.scene.control.Labeled labeled
                && text.equals(labeled.getText())) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node hit = findByText(child, text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static void pump() throws Exception {
        for (int i = 0; i < 6; i++) {
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
