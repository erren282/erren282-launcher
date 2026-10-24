package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shows why the status label had to be pinned, by measuring the text itself.
 *
 * <p>This is the mechanism behind the reported "the dialog covers the launcher after
 * searching" problem: an unwrapped {@code Label} is as wide as its text, so a long
 * message made the column — and with it the dialog — wider. If the unpinned width is
 * greater than the pinned width, pinning is what keeps the dialog one size.</p>
 *
 * <p>Complements {@link DialogSizeTest}, which measures the assembled dialog.</p>
 */
public final class StatusWidthTest {

    private static int passed;
    private static int failed;

    private static final String LONG = "皮肤站禁止匿名搜索，已扫描最热门的 800 个皮肤，找到 16 个匹配"
            + "「averyveryverylongkeywordthatshouldnotstretchanything」的（可能还有更多）。";

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        System.out.println("=== 未固定的状态标签会因文字变宽（这就是弹窗被撑大的原因）===");

        double unpinnedLong = measure(LONG, false, -1);
        double unpinnedShort = measure("正在搜索…", false, -1);
        System.out.printf("  未固定 + 长文字: %.0f px%n", unpinnedLong);
        System.out.printf("  未固定 + 短文字: %.0f px%n", unpinnedShort);

        check("a long message really is wider than a short one when unpinned",
                unpinnedLong > unpinnedShort + 50);
        check("and it exceeds the column width, so it would stretch the dialog",
                unpinnedLong > LittleSkinLibraryPaneColumnWidth());

        System.out.println();
        System.out.println("=== 固定宽度后两者一致 ===");
        double pinnedLong = measure(LONG, true, LittleSkinLibraryPaneColumnWidth());
        double pinnedShort = measure("正在搜索…", true, LittleSkinLibraryPaneColumnWidth());
        System.out.printf("  固定 + 长文字: %.0f px%n", pinnedLong);
        System.out.printf("  固定 + 短文字: %.0f px%n", pinnedShort);

        check("a pinned label takes exactly the column width", pinnedLong == pinnedShort);
        check("pinning is what stops the stretch", pinnedLong < unpinnedLong);

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    /// The column width the pane pins its controls to.
    private static double LittleSkinLibraryPaneColumnWidth() throws Exception {
        AtomicReference<Double> width = new AtomicReference<>();
        onFx(() -> {
            LittleSkinLibraryPane pane = new LittleSkinLibraryPane(
                    LittleSkinLibraryPane.Filter.SKIN,
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
                    path -> { }, false, 492);
            width.set(pane.listWidthForTest());
        });
        pump(4);
        return width.get();
    }

    /** Measures a label's preferred width, optionally wrapped and pinned. */
    private static double measure(String text, boolean wrapped, double pin) throws Exception {
        AtomicReference<Double> width = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        onFx(() -> {
            Label label = new Label(text);
            label.setWrapText(wrapped);
            if (pin > 0) {
                label.setPrefWidth(pin);
                label.setMinWidth(pin);
                label.setMaxWidth(pin);
            }
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(label), 1400, 200));
            stage.show();
            stageRef.set(stage);
            width.set(label.prefWidth(-1));
        });
        pump(6);
        onFx(() -> {
            stageRef.get().close();
            stageRef.get().getScene().setRoot(new StackPane());
        });
        pump(3);
        return width.get();
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
