package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Checks that the library dialog keeps one size, whatever the status message says.
 *
 * <h2>Why this is measured</h2>
 * <p>The reported problem was that the dialog grew after a search until it covered
 * the launcher window. The cause was an unwrapped, unbounded status label: its text
 * set the width of the column, which set the width of the dialog. Assertions on the
 * code would not have caught that — the size has to be laid out and read back.</p>
 *
 * <p>This builds the dialog, lays it out offscreen at a deliberately long message and
 * at a short one, and compares the measured sizes.</p>
 */
public final class DialogSizeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        // Without this, closing the last stage fires JavaFX's implicit exit, which shuts
        // the FX thread down — every later runLater then waits forever and the test
        // reports a timeout instead of a result. This is why the measurements must be
        // able to close their windows between runs.
        Platform.setImplicitExit(false);
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            sizeChecks();
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

    private static void sizeChecks() throws Exception {
        System.out.println("=== 状态文字长度不影响弹窗尺寸 ===");

        String short_ = "正在搜索…";
        String long_ = "皮肤站禁止匿名搜索，已扫描最热门的 800 个皮肤，找到 16 个匹配"
                + "「averyveryverylongkeywordthatshouldnotstretchanything」的（可能还有更多）。";

        double[] shortSize = measureWithStatus(short_);
        double[] longSize = measureWithStatus(long_);

        System.out.printf("  短消息: %.0f x %.0f%n", shortSize[0], shortSize[1]);
        System.out.printf("  长消息: %.0f x %.0f%n", longSize[0], longSize[1]);

        check("the width does not depend on the message",
                Math.abs(shortSize[0] - longSize[0]) < 1.0);
        check("the height does not depend on the message",
                Math.abs(shortSize[1] - longSize[1]) < 1.0);
        check("the dialog is not as wide as a launcher window (<= 700 px)",
                shortSize[0] <= 700);
        check("the dialog is not as tall as a launcher window (<= 600 px)",
                shortSize[1] <= 600);

        System.out.println();
        System.out.println("=== 列表为空与有内容时尺寸相同 ===");
        double[] empty = measureWithStatus(long_, true);
        double[] filled = measureWithStatus(short_, false);
        System.out.printf("  空列表: %.0f x %.0f%n", empty[0], empty[1]);
        System.out.printf("  有内容: %.0f x %.0f%n", filled[0], filled[1]);
        check("an empty result list does not shrink the dialog",
                Math.abs(empty[0] - filled[0]) < 1.0);
        check("and does not change its height",
                Math.abs(empty[1] - filled[1]) < 1.0);
    }

    /**
     * Lays the dialog out with a given status message and returns its size.
     *
     * <p>One pane per measurement, each closed before the next. The pane's constructor
     * starts a background fetch that keeps posting work to the FX thread; leaving
     * earlier panes alive made those posts pile up until a later {@code runLater}
     * never got its turn, which showed up as a timeout rather than as a failure.</p>
     */
    private static double[] measureWithStatus(String status) throws Exception {
        return measureWithStatus(status, false);
    }

    private static double[] measureWithStatus(String status, boolean emptyList) throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<double[]> size = new AtomicReference<>();

        onFx(() -> {
            // Build the real pane, but without its initial fetch: that starts a
            // background thread which keeps posting to the FX thread, and it starved
            // this test's own runLater calls. Nothing about the layout depends on it.
            LittleSkinLibraryPane pane = new LittleSkinLibraryPane(
                    LittleSkinLibraryPane.Filter.SKIN,
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
                    path -> { }, false, 492);

            // Drive the two things a search changes: the status text, and whether the
            // list has content. Done through the pane's own seams rather than by
            // walking the scene graph, so the test cannot silently stop testing
            // anything if the layout is restructured.
            pane.setStatusTextForTest(status);
            if (emptyList) {
                pane.clearListForTest();
            }

            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(pane), 1200, 900));
            stage.show();
            stageRef.set(stage);
        });
        pump(10);
        Thread.sleep(120);
        pump(5);

        onFx(() -> {
            Node pane = stageRef.get().getScene().getRoot().getChildrenUnmodifiable().get(0);
            // Measure the *preferred* size, not the layout bounds: the pane is stretched
            // to fill the test scene, while the dialog sizes itself from the preferred
            // size — which is exactly what the reported problem was about.
            double width = pane.prefWidth(-1);
            size.set(new double[]{width, pane.prefHeight(width)});
        });
        // Close and let the background fetch finish before the next measurement.
        onFx(() -> {
            stageRef.get().close();
            stageRef.get().getScene().setRoot(new StackPane());
        });
        pump(5);
        return size.get();
    }

    private static <T> List<T> findAll(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                found.addAll(findAll(child, type));
            }
        }
        return found;
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
