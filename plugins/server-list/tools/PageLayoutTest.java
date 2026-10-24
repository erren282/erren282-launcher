package org.dsh.serverlist;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Verifies the two layout changes to the server list page.
 *
 * <h2>What is checked</h2>
 * <ol>
 *   <li><b>One back arrow, not two.</b> The module's 「返回」 used to be its own row above the
 *       heading, directly under the page title's arrow — two arrows doing the same job. The
 *       heading now carries the arrow on its title line, and the separate row is gone. A
 *       regression here is visually obvious to the user but easy to reintroduce by adding a
 *       header row back, so it is asserted structurally.</li>
 *   <li><b>The heading arrow actually fires.</b> A back button that looks right but is wired
 *       to nothing is worse than a missing one.</li>
 * </ol>
 */
public final class PageLayoutTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        pump();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            headingCarriesTheArrow();
            noHeadingWithoutBack();
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

    /// The arrow-adding heading overload works, when a caller asks for one.
    ///
    /// <p>The server list page itself no longer asks for it (the module entry already has a
    /// 「返回」), so this now guards the overload itself rather than the page's use of it: the
    /// widget is still available, and it must place the arrow on the title line rather than on
    /// a row of its own, which is the duplication that started this.</p>
    private static void headingCarriesTheArrow() throws Exception {
        System.out.println("=== the arrow-adding overload puts one arrow on the title line ===");

        AtomicReference<Boolean> fired = new AtomicReference<>(false);
        VBox heading = onFxGet(() -> ListUi.heading("服务器列表", "说明文字",
                () -> fired.set(true)));

        List<Button> buttons = onFxGet(() -> buttonsIn(heading));
        System.out.println("  buttons in the heading: " + buttons.size());
        check("the heading contains exactly one back button", buttons.size() == 1);
        check("that button has an arrow graphic",
                buttons.size() == 1 && buttons.get(0).getGraphic() != null);
        check("it carries a tooltip", buttons.size() == 1
                && buttons.get(0).getTooltip() != null);

        System.out.println();
        System.out.println("=== the arrow sits on the title line, not its own row ===");
        // The heading is a VBox whose single child is the title line; the arrow has to be
        // inside that line, next to the title, or it would read as a separate toolbar again.
        int rows = onFxGet(() -> heading.getChildren().size());
        System.out.println("  heading rows: " + rows);
        check("the heading is one row", rows == 1);
        boolean inLine = onFxGet(() -> heading.getChildren().get(0) instanceof HBox
                && ((HBox) heading.getChildren().get(0)).getChildren().stream()
                        .anyMatch(n -> n instanceof Button));
        check("the arrow is inside that row", inLine);

        System.out.println();
        System.out.println("=== clicking it runs the back action ===");
        onFx(() -> buttons.get(0).fire());
        check("the action ran", Boolean.TRUE.equals(fired.get()));
    }

    /// A heading without a back action must not gain an arrow.
    private static void noHeadingWithoutBack() throws Exception {
        System.out.println();
        System.out.println("=== a heading with no back action has no arrow ===");

        VBox heading = onFxGet(() -> ListUi.heading("服务器列表", "说明文字"));
        List<Button> buttons = onFxGet(() -> buttonsIn(heading));
        check("no button is added", buttons.isEmpty());

        List<Label> labels = onFxGet(() -> labelsIn(heading));
        check("the title is still shown", labels.stream()
                .anyMatch(l -> "服务器列表".equals(l.getText())));
        check("the subtitle is still shown", labels.stream()
                .anyMatch(l -> "说明文字".equals(l.getText())));
    }

    // ----------------------------------------------------------------- helpers

    /// Every button under a node.
    private static List<Button> buttonsIn(Node node) {
        List<Button> found = new ArrayList<>();
        collect(node, Button.class, found);
        return found;
    }

    /// Every label under a node.
    private static List<Label> labelsIn(Node node) {
        List<Label> found = new ArrayList<>();
        collect(node, Label.class, found);
        return found;
    }

    /// Depth-first search for nodes of one type.
    private static <T> void collect(Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node))
            into.add(type.cast(node));
        if (node instanceof javafx.scene.Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                collect(child, type, into);
    }

    // --------------------------------------------------------- FX marshalling

    private static void pump() throws Exception {
        pump(6);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++)
            onFx(() -> { });
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
        if (!done.await(30, TimeUnit.SECONDS))
            throw new IllegalStateException("FX action timed out");
        if (thrown.get() != null)
            throw new IllegalStateException("FX action threw", thrown.get());
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
