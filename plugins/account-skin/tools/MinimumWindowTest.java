package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Checks that the skin dialogs fit the launcher's smallest window.
 *
 * <h2>Why this is measured</h2>
 * <p>The reported problem was that the dialog looked out of proportion when the
 * launcher was at its minimum size. The launcher's minimum content area is fixed at
 * {@code 802 x 492} ({@code Controllers.MIN_CONTENT_WIDTH/HEIGHT}), so anything taller
 * than 492 px cannot fit and the proportions break.</p>
 *
 * <p>The skin-library dialog measured 524 px tall before the fix — 32 px too tall.
 * That is not something a code review would have caught, so the sizes are measured.</p>
 */
public final class MinimumWindowTest {

    /// The launcher's minimum content area, from Controllers.
    private static final double MIN_CONTENT_WIDTH = 802;
    private static final double MIN_CONTENT_HEIGHT = 492;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
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
        System.out.println("=== 最小窗口 " + (int) MIN_CONTENT_WIDTH + "x"
                + (int) MIN_CONTENT_HEIGHT + " 下弹窗必须放得下 ===");

        // The size the dialog reports at the smallest window, which is what makes the
        // proportions wrong. 0 means "the smallest window".
        double[] library = measureLibraryPane(MIN_CONTENT_HEIGHT);
        System.out.printf("  皮肤库弹窗: %.0f x %.0f%n", library[0], library[1]);
        check("the library dialog fits the minimum width",
                library[0] <= MIN_CONTENT_WIDTH);
        check("the library dialog fits the minimum height",
                library[1] <= MIN_CONTENT_HEIGHT);

        System.out.println();
        System.out.println("=== 窗口变大时不应无限变大 ===");
        double[] huge = measureLibraryPane(2000);
        System.out.printf("  皮肤库弹窗（大窗口）: %.0f x %.0f%n", huge[0], huge[1]);
        check("the list column keeps its width", Math.abs(huge[0] - library[0]) < 1.0);
        check("the dialog grows with a bigger window", huge[1] > library[1]);
        check("but stays reasonable (<= 800 px)", huge[1] <= 800);

        System.out.println();
        System.out.println("=== 极小的可用高度也不会压扁 ===");
        double[] tiny = measureLibraryPane(300);
        System.out.printf("  皮肤库弹窗（可用高度 300）: %.0f x %.0f%n", tiny[0], tiny[1]);
        check("the list keeps a usable minimum height", tiny[1] > 200);
        check("the width is unchanged", Math.abs(tiny[0] - library[0]) < 1.0);

        System.out.println();
        System.out.println("=== 皮肤/披风主弹窗 ===");
        double[] skinDialog = measureSkinCapeDialog();
        System.out.printf("  切换皮肤弹窗: %.0f x %.0f%n", skinDialog[0], skinDialog[1]);
        check("it fits the minimum width", skinDialog[0] <= MIN_CONTENT_WIDTH);
        check("it fits the minimum height", skinDialog[1] <= MIN_CONTENT_HEIGHT);
    }

    /** The library pane's preferred size when the available height is {@code height}. */
    private static double[] measureLibraryPane(double availableHeight) throws Exception {
        return measure(() -> new LittleSkinLibraryPane(
                LittleSkinLibraryPane.Filter.SKIN,
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
                path -> { }, false, availableHeight));
    }

    private static double[] measureSkinCapeDialog() throws Exception {
        return measure(() -> new SkinCapeDialog(
                offlineAccount(), SkinCapeDialog.Mode.SKIN,
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))));
    }

    /** An offline account, which needs no network and no credentials. */
    private static org.jackhuang.hmcl.auth.offline.OfflineAccount offlineAccount() {
        org.jackhuang.hmcl.auth.offline.OfflineAccountFactory factory =
                new org.jackhuang.hmcl.auth.offline.OfflineAccountFactory(
                        new org.jackhuang.hmcl.auth.authlibinjector.SimpleAuthlibInjectorArtifactProvider(
                                java.nio.file.Paths.get("authlib-injector.jar")));
        return factory.create("qwen",
                java.util.UUID.nameUUIDFromBytes("qwen".getBytes()));
    }

    /** Lays a node out offscreen and returns its preferred size. */
    private static double[] measure(Supplier<Node> factory) throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<double[]> size = new AtomicReference<>();
        onFx(() -> {
            Node node = factory.get();
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(node), 1400, 1000));
            stage.show();
            stageRef.set(stage);
        });
        pump(8);
        onFx(() -> {
            Node node = stageRef.get().getScene().getRoot().getChildrenUnmodifiable().get(0);
            double width = node.prefWidth(-1);
            size.set(new double[]{width, node.prefHeight(width)});
        });
        onFx(() -> {
            stageRef.get().close();
            stageRef.get().getScene().setRoot(new StackPane());
        });
        pump(4);
        return size.get();
    }

    // --------------------------------------------------------- FX marshalling

    private static void pump() throws Exception {
        pump(4);
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
