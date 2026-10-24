package org.dsh.modtoversion;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialog;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.ui.construct.JFXDialogPane;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Verifies that the version picker stays operable when the launcher window is as small as
 * the launcher allows.
 *
 * <h2>The bug this guards against</h2>
 * <p>The dialog is laid out inside a {@link JFXDialogPane}, which is a plain
 * {@link StackPane}: anything taller than the pane is <em>clipped</em>, not scrolled. With
 * the rows placed directly in an unbounded {@code VBox} and enough installed versions to
 * list, the dialog was squeezed until its action row had no height left — the 取消 button
 * was unclickable and the dialog could not be dismissed. A user reported exactly that for
 * a minimised launcher window.</p>
 *
 * <p>These checks drive the <em>real</em> {@link TargetVersionDialog}, not a copy of it, and
 * assert the property that makes the dialog usable: the action row keeps a real size and
 * stays inside the window.</p>
 */
public final class DialogSizingTest {

    /// The launcher's minimum content height, from {@code Controllers.MIN_CONTENT_HEIGHT}.
    private static final double MIN_WINDOW_HEIGHT = 450 + 2 + 40;

    /// The launcher's minimum content width, from {@code Controllers.MIN_CONTENT_WIDTH}.
    private static final double MIN_WINDOW_WIDTH = 800 + 2;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            heightBudgetChecks();
            manyVersionsStayOperable();
            fewVersionsKeepTheButton();
            wideWindowGivesMoreRoom();
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

    /// The budget itself: the list may never claim more height than the window can spare.
    private static void heightBudgetChecks() {
        System.out.println("=== the list's height budget follows the window ===");

        double tiny = TargetVersionDialog.maxListHeight(MIN_WINDOW_HEIGHT);
        System.out.printf("  window %.0f -> list cap %.0f%n", MIN_WINDOW_HEIGHT, tiny);
        check("a minimum-size window still yields a usable list",
                tiny >= TargetVersionDialog.MIN_LIST_HEIGHT);
        check("but not more of it than the window can spare", tiny <= MIN_WINDOW_HEIGHT);

        System.out.println();
        System.out.println("=== an unknown or absurd window height is handled ===");
        check("no window yields the floor, not a negative height",
                TargetVersionDialog.maxListHeight(-1) == TargetVersionDialog.MIN_LIST_HEIGHT);
        check("NaN yields the floor too",
                TargetVersionDialog.maxListHeight(Double.NaN)
                        == TargetVersionDialog.MIN_LIST_HEIGHT);
        check("a zero height yields the floor",
                TargetVersionDialog.maxListHeight(0) == TargetVersionDialog.MIN_LIST_HEIGHT);

        System.out.println();
        System.out.println("=== a taller window is allowed more list ===");
        double normal = TargetVersionDialog.maxListHeight(900);
        System.out.printf("  window 900 -> list cap %.0f%n", normal);
        check("a 900px window gets a taller list than a minimum one", normal > tiny);
    }

    /// The case that was reported: many versions, a minimum-size window, and the cancel
    /// button has to remain clickable.
    private static void manyVersionsStayOperable() throws Exception {
        System.out.println();
        System.out.println("=== 20 versions in a minimum-size window ===");

        List<VersionMatcher.Installed> many = installedVersions(20);
        System.out.println("  versions offered: " + many.size());

        Layout layout = show(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT, many);
        System.out.printf("  window content: %.0fx%.0f%n", MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
        System.out.printf("  dialog %.0fx%.0f at scene y=%.0f..%.0f%n",
                layout.dialogWidth(), layout.dialogHeight(),
                layout.dialog().getMinY(), layout.dialog().getMaxY());
        System.out.printf("  cancel scene y=%.0f..%.0f (h=%.0f)%n",
                layout.cancel().getMinY(), layout.cancel().getMaxY(),
                layout.cancel().getHeight());
        System.out.printf("  list height: %.0f%n", layout.listHeight());

        check("the dialog was laid out at all", layout.dialogHeight() > 0);
        check("the dialog fits inside the window vertically",
                layout.dialog().getMaxY() <= MIN_WINDOW_HEIGHT + 1);
        // This is the regression: before the fix the button had zero height.
        check("the cancel button keeps a real height", layout.cancel().getHeight() > 10);
        check("the cancel button keeps a real width", layout.cancel().getWidth() > 10);
        check("the cancel button is inside the dialog",
                layout.cancel().getMaxY() <= layout.dialog().getMaxY() + 1);
        check("the whole cancel button is inside the window",
                layout.cancel().getMaxY() <= MIN_WINDOW_HEIGHT + 1);
        check("the list was actually capped", layout.listHeight() > 0);
        check("the list is shorter than the whole dialog",
                layout.listHeight() < layout.dialogHeight());
        // The scroll structure is what keeps the dialog operable: without it the rows are
        // laid out at full height in the dialog and the action row gets squeezed away.
        // Measured on the pre-fix layout, twelve rows left the cancel button 0px tall.
        check("the rows are inside a scroll pane, not the dialog body directly",
                layout.hasScrollPane());
        // The cap itself, read off the live node: a finite maximum height is what stops
        // the list from growing the dialog. This is checked on the node rather than against
        // its content because the rows are styled by the launcher's stylesheet, which a
        // bare test scene does not load, so their laid-out height is not representative.
        check("the list's maximum height is capped, not unbounded",
                layout.listMaxHeight() > 0 && layout.listMaxHeight() < Double.MAX_VALUE);
        check("the cap matches what the window allows",
                Math.abs(layout.listMaxHeight()
                        - TargetVersionDialog.maxListHeight(MIN_WINDOW_HEIGHT)) < 1);
        layout.close();
    }

    /// One version must not be capped down to an unreasonable list either.
    private static void fewVersionsKeepTheButton() throws Exception {
        System.out.println();
        System.out.println("=== a single version still shows a proper list ===");

        Layout layout = show(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT, installedVersions(1));
        System.out.printf("  dialog %.0fx%.0f, list %.0f%n",
                layout.dialogWidth(), layout.dialogHeight(), layout.listHeight());
        check("the dialog is no taller than the window",
                layout.dialogHeight() <= MIN_WINDOW_HEIGHT + 1);
        check("the list still has its minimum height",
                layout.listHeight() >= TargetVersionDialog.MIN_LIST_HEIGHT - 1);
        check("the cancel button keeps a real height", layout.cancel().getHeight() > 10);
        layout.close();
    }

    /// A larger window must actually give the list more room, or the cap is wrong.
    private static void wideWindowGivesMoreRoom() throws Exception {
        System.out.println();
        System.out.println("=== a taller window gives the list more room ===");

        Layout small = show(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT, installedVersions(30));
        Layout large = show(MIN_WINDOW_WIDTH, 900, installedVersions(30));
        System.out.printf("  list at 492: %.0f   list at 900: %.0f%n",
                small.listHeight(), large.listHeight());
        check("the taller window gets a taller list",
                large.listHeight() > small.listHeight());
        check("both keep the cancel button clickable",
                small.cancel().getHeight() > 10 && large.cancel().getHeight() > 10);

        small.close();
        large.close();
    }

    // ----------------------------------------------------------------- helpers

    /// What a laid-out dialog measured, in scene coordinates.
    private record Layout(JFXDialogLayout content, Bounds dialogBounds, Bounds cancelBounds,
                          double dialogWidth, double dialogHeight, double listHeight,
                          boolean hasScrollPane, double listMaxHeight, Stage stage) {

        Bounds dialog() {
            return dialogBounds;
        }

        Bounds cancel() {
            return cancelBounds;
        }

        void close() {
            onFxQuietly(stage::close);
        }
    }

    /// Shows the real dialog in a window of the given size and measures it.
    private static Layout show(double windowWidth, double windowHeight,
                               List<VersionMatcher.Installed> targets) throws Exception {
        JFXDialogLayout[] holder = new JFXDialogLayout[1];
        Stage stage = onFxGet(() -> {
            StackPane wrapper = new StackPane();
            Stage window = new Stage();
            window.setScene(new Scene(wrapper, windowWidth, windowHeight));
            window.show();

            JFXDialog dialog = new JFXDialog();
            JFXDialogPane pane = new JFXDialogPane();
            dialog.setContent(pane);
            dialog.setDialogContainer(wrapper);
            dialog.show();

            // The real dialog, told how tall the window is.
            JFXDialogLayout content = new TargetVersionDialog(
                    AddonKind.RESOURCE_PACK, release("Faithful 32x"), targets,
                    target -> { }, windowHeight);
            holder[0] = content;
            pane.push(content);
            return window;
        });

        // Layout needs a few pulses once the scene is realised.
        pump(20);
        Thread.sleep(400);
        pump(10);

        Bounds dialogBounds = onFxGet(
                () -> holder[0].localToScene(holder[0].getLayoutBounds()));
        Bounds cancelBounds = onFxGet(() -> {
            Node cancel = cancelButton(holder[0]);
            return cancel.localToScene(cancel.getLayoutBounds());
        });
        double width = onFxGet(() -> holder[0].getWidth());
        double height = onFxGet(() -> holder[0].getHeight());
        ScrollPane[] scrollHolder = new ScrollPane[1];
        double listHeight = onFxGet(() -> {
            ScrollPane scroll = findScrollOf(holder[0]);
            scrollHolder[0] = scroll;
            return scroll == null ? -1 : scroll.getHeight();
        });
        boolean hasScroll = scrollHolder[0] != null;
        double listMaxHeight = onFxGet(() ->
                scrollHolder[0] == null ? -1 : scrollHolder[0].getMaxHeight());

        return new Layout(holder[0], dialogBounds, cancelBounds, width, height,
                listHeight, hasScroll, listMaxHeight, stage);
    }

    /// The dialog's scroll pane, or null when there is none.
    private static ScrollPane findScrollOf(JFXDialogLayout layout) {
        for (Node node : layout.lookupAll(".scroll-pane")) {
            if (node instanceof ScrollPane scroll) {
                return scroll;
            }
        }
        return null;
    }

    /// Installed versions numbered so the rows are distinct.
    private static List<VersionMatcher.Installed> installedVersions(int count) {
        List<VersionMatcher.Installed> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new VersionMatcher.Installed("1.20." + i + "-Fabric", "1.20." + i,
                    Set.of(ModLoaderType.FABRIC)));
        }
        return list;
    }

    /// The dialog's cancel button.
    ///
    /// <p>Found through the scene graph: once the dialog is laid out,
    /// {@code JFXDialogLayout}'s skin moves the action nodes into its own flow pane and the
    /// list {@code getActions()} returns is left empty.</p>
    private static JFXButton cancelButton(JFXDialogLayout layout) {
        Node found = layout.lookup(".dialog-cancel");
        if (found instanceof JFXButton button) {
            return button;
        }
        throw new IllegalStateException("no cancel button found in the dialog");
    }

    /// The dialog's version list.
    private static ScrollPane scrollOf(JFXDialogLayout layout) {
        ScrollPane found = findScrollOf(layout);
        if (found == null) {
            throw new IllegalStateException("no scroll pane found in the dialog");
        }
        return found;
    }

    /// A release the picker could be showing.
    private static RemoteAddon.Version release(String name) {
        return new RemoteAddon.Version(
                null, "example", name, name, "",
                Instant.parse("2026-05-01T00:00:00Z"),
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/x.zip", "x.zip"),
                List.of(), List.of("1.20.4"), List.of());
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

    private static void onFxQuietly(Runnable action) {
        try {
            onFx(action);
        } catch (Exception ignored) {
            // Closing a test window must not fail the run.
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
