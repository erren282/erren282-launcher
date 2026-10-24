package org.dsh.serverlist;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Verifies the real server list page, not a copy of its pieces.
 *
 * <h2>Why the real page</h2>
 * <p>The heading test proves the heading widget is right, but not that the page uses it — the
 * page could still add its own extra back row, which is exactly the state the user reported
 * (two arrows, one above the other). So this builds an actual {@link ServerListPage} and counts
 * the back arrows in it.</p>
 *
 * <h2>What is checked</h2>
 * <ul>
 *   <li><b>no back arrow</b> in the page's heading — the module entry provides 「返回」, and a
 *       second one in the heading is the duplication the user reported;</li>
 *   <li>the title is still shown, so removing the arrow did not remove the heading;</li>
 *   <li>the smooth-scrolling hook finds the enclosing scroll pane once the page is mounted,
 *       which is the part that cannot work from the constructor.</li>
 * </ul>
 */
public final class ServerListPageTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // The page is built from launcher widgets whose static initialisers read the launcher
        // settings (RipplerContainer, animation flags). A real launcher has them loaded before
        // any UI exists; a test has to do the same or constructing the page throws.
        org.jackhuang.hmcl.setting.SettingsManager.init();
        Platform.startup(() -> { });
        pump();

        Path work = Files.createTempDirectory("server-list-test-");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run(work);
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            deleteTree(work);
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

    private static void run(Path work) throws Exception {
        arrowCountChecks(work);
        scrollHookChecks(work);
    }

    /// The page must not carry a back arrow in its heading.
    ///
    /// <p>History: the page first had two arrows (a back row plus the heading's), which became
    /// one arrow merged into the heading, and was then removed entirely at the user's request —
    /// the module entry already provides 「返回」. So the assertion is now "zero", and the point
    /// of counting is that an arrow added back here goes unnoticed.</p>
    private static void arrowCountChecks(Path work) throws Exception {
        System.out.println("=== the real page shows no back arrow ===");

        AtomicReference<Boolean> wentBack = new AtomicReference<>(false);
        ServerListPage[] holder = new ServerListPage[1];

        Stage stage = onFxGet(() -> {
            ServerListPage page = new ServerListPage(work, () -> wentBack.set(true));
            holder[0] = page;

            StackPane wrapper = new StackPane(page);
            Stage window = new Stage();
            window.setScene(new Scene(wrapper, 900, 700));
            window.show();
            return window;
        });

        pump(20);
        Thread.sleep(300);
        pump(5);

        List<Button> arrows = onFxGet(() -> arrowsIn(holder[0]));
        System.out.println("  back arrows found: " + arrows.size());
        check("the page has no back arrow", arrows.isEmpty());

        boolean hasTitle = onFxGet(() -> textIn(holder[0]).contains("服务器列表"));
        check("the page still shows its title", hasTitle);
        check("the module's back action was not fired by showing the page",
                Boolean.FALSE.equals(wentBack.get()));

        onFx(stage::close);
    }

    /// The scroll hook has to find the pane after mounting, not during construction.
    private static void scrollHookChecks(Path work) throws Exception {
        System.out.println();
        System.out.println("=== smooth scrolling attaches to the shell's scroll pane ===");

        AtomicReference<ServerListPage> holder = new AtomicReference<>();

        Stage stage = onFxGet(() -> {
            ServerListPage page = new ServerListPage(work, () -> { });
            holder.set(page);

            // Mimic the launcher shell: the page lives inside a ScrollPane.
            ScrollPane scroll = new ScrollPane(page);
            scroll.setFitToWidth(true);
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(scroll), 900, 700));
            window.show();
            return window;
        });

        pump(20);
        Thread.sleep(400);
        pump(10);

        boolean found = onFxGet(() -> ancestorScrollPaneOf(holder.get()) != null);
        check("the page can locate the enclosing scroll pane", found);

        boolean flag = onFxGet(() -> smoothScrollingFlag(holder.get()));
        System.out.println("  smooth-scrolling flag after mounting: " + flag);
        check("the hook marked the page as configured", flag);

        System.out.println();
        System.out.println("=== a page with no scroll pane does not break ===");
        // The launcher may host the page somewhere without a scroll pane; the hook must give
        // up quietly rather than throw during layout.
        AtomicReference<ServerListPage> loose = new AtomicReference<>();
        Stage looseStage = onFxGet(() -> {
            ServerListPage page = new ServerListPage(work, () -> { });
            loose.set(page);
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(page), 900, 700));
            window.show();
            return window;
        });
        pump(20);
        Thread.sleep(200);
        pump(5);
        check("no scroll pane means no exception and no flag",
                !onFxGet(() -> smoothScrollingFlag(loose.get())));

        onFx(looseStage::close);
        onFx(stage::close);
    }

    // ----------------------------------------------------------------- helpers

    /// Buttons that act as a back arrow.
    ///
    /// <p>Matched by what the button says, not by whether it has a graphic: every server card
    /// carries an 「进入游戏」 button with its own icon, so counting "has a graphic" counted all
    /// of them. A back arrow is identified by its label (返回…) or by the arrow style class the
    /// launcher puts on it.</p>
    private static List<Button> arrowsIn(Node node) {
        List<Button> found = new ArrayList<>();
        for (Button button : nodesOfType(node, Button.class))
            if (isBackArrow(button))
                found.add(button);
        return found;
    }

    /// True when a button is a back arrow rather than some other icon button.
    private static boolean isBackArrow(Button button) {
        String text = button.getText();
        if (text != null && text.contains("返回"))
            return true;
        return button.getStyleClass().contains("dialog-accept")
                && button.getGraphic() != null
                && button.getTooltip() != null;
    }

    /// All label texts under a node.
    private static List<String> textIn(Node node) {
        List<String> texts = new ArrayList<>();
        for (javafx.scene.control.Label label : nodesOfType(node, javafx.scene.control.Label.class))
            if (label.getText() != null)
                texts.add(label.getText());
        return texts;
    }

    /// Every node of one type under a root.
    private static <T> List<T> nodesOfType(Node node, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(node, type, found);
        return found;
    }

    private static <T> void collect(Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node))
            into.add(type.cast(node));
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                collect(child, type, into);
    }

    /// The nearest ancestor scroll pane, mirroring the page's own lookup.
    private static ScrollPane ancestorScrollPaneOf(Node node) {
        for (Node current = node.getParent(); current != null; current = current.getParent())
            if (current instanceof ScrollPane pane)
                return pane;
        return null;
    }

    /// Reads the page's private smooth-scrolling flag.
    ///
    /// <p>Reflection failures are reported as false rather than thrown, because this runs
    /// inside a {@code Supplier} that cannot carry a checked exception.</p>
    private static boolean smoothScrollingFlag(ServerListPage page) {
        try {
            var field = ServerListPage.class.getDeclaredField("smoothScrollingEnabled");
            field.setAccessible(true);
            return field.getBoolean(page);
        } catch (Throwable t) {
            return false;
        }
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

    private static void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir))
            return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
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
