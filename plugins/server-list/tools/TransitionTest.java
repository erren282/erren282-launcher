package org.dsh.serverlist;

import org.jackhuang.hmcl.setting.SettingsManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Verifies that switching views on the server list page animates instead of snapping.
 *
 * <h2>What is checked</h2>
 * <p>The content area is a [org.jackhuang.hmcl.ui.animation.TransitionPane], so a view change
 * goes through the launcher's own transition. What matters is that it is actually used:</p>
 * <ul>
 *   <li>the content area is a TransitionPane at all;</li>
 *   <li>clicking 发现更多服务器 and the back button each play a transition — a snap replacement
 *       leaves the pane with a single, resting child, while a running transition leaves two
 *       children with the incoming one still offset and transparent;</li>
 *   <li>the navigation still works, because a transition that swallowed the content would look
 *       right in code and empty on screen.</li>
 * </ul>
 */
public final class TransitionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
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
        Path work = Files.createTempDirectory("server-list-transition-");
        try {
            contentAreaIsAPane(work);
            navigatingAnimates(work);
        } finally {
            deleteTree(work);
        }
    }

    /// The content area must be a TransitionPane.
    private static void contentAreaIsAPane(Path work) throws Exception {
        System.out.println("=== the content area is a transition pane ===");

        ServerListPage[] holder = new ServerListPage[1];
        Stage stage = onFxGet(() -> {
            ServerListPage page = new ServerListPage(work, () -> { });
            holder[0] = page;
            Stage window = new Stage();
            window.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(page),
                    900, 700));
            window.show();
            return window;
        });

        String type = onFxGet(() -> contentViewType(holder[0]));
        System.out.println("  content area type: " + type);
        check("the content area is a TransitionPane", "TransitionPane".equals(type));

        boolean showsList = onFxGet(() -> textIn(holder[0]).contains("服务器列表"));
        check("the list view is displayed initially", showsList);

        onFx(stage::close);
    }

    /// Clicking into the directory and back must each animate.
    private static void navigatingAnimates(Path work) throws Exception {
        System.out.println();
        System.out.println("=== navigating animates in both directions ===");

        ServerListPage[] holder = new ServerListPage[1];
        Stage stage = onFxGet(() -> {
            ServerListPage page = new ServerListPage(work, () -> { });
            holder[0] = page;
            Stage window = new Stage();
            window.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(page),
                    900, 700));
            window.show();
            return window;
        });

        pump(20);
        Thread.sleep(200);
        pump(5);

        boolean listFirst = onFxGet(() -> textIn(holder[0]).contains("刷新状态"));
        check("the list view was shown before clicking", listFirst);

        // The first setContent call happens in the constructor, before the pane has a size,
        // so that one cannot animate (TransitionPane's own guard). What matters is the switch
        // the user performs once the pane is laid out.
        //
        // 页内已经不再画返回按钮，返回只有一条路：窗口左上角的原生箭头 ——
        // 它最终调用 page.back()，所以这里用同一个入口来驱动，而不是去找一个按钮。
        Node action = onFxGet(() -> findAction(holder[0], "发现更多服务器"));
        if (action == null) {
            check("the 发现更多服务器 action was found", false);
        } else {
            String before = onFxGet(() -> describePane(holder[0]));
            onFx(() -> action.fireEvent(new javafx.event.ActionEvent()));
            // TransitionPane builds its animation inside a runLater, so it does not exist in
            // the same pulse as the click; one pulse later it is playing.
            onFx(() -> { });
            String after = onFxGet(() -> describePane(holder[0]));
            System.out.println("  发现更多服务器");
            System.out.println("    before: " + before);
            System.out.println("    after:  " + after);
            check("进入在线目录 plays a transition", isAnimating(holder[0]));
            pump(15);
        }

        String beforeBack = onFxGet(() -> describePane(holder[0]));
        onFx(() -> holder[0].back());
        onFx(() -> { });
        String afterBack = onFxGet(() -> describePane(holder[0]));
        System.out.println("  返回（左上角原生箭头）");
        System.out.println("    before: " + beforeBack);
        System.out.println("    after:  " + afterBack);
        check("返回列表 plays a transition", isAnimating(holder[0]));
        pump(15);

        boolean backToList = onFxGet(() -> textIn(holder[0]).contains("刷新状态"));
        check("returning restores the list view", backToList);

        onFx(stage::close);
    }

    // ----------------------------------------------------------------- helpers

    /// Reads the content area's type name reflectively.
    private static String contentViewType(ServerListPage page) {
        try {
            var field = ServerListPage.class.getDeclaredField("contentView");
            field.setAccessible(true);
            Object value = field.get(page);
            return value == null ? "null" : value.getClass().getSimpleName();
        } catch (Throwable t) {
            return "<unreadable>";
        }
    }

    /// True while the content pane is mid-transition.
    ///
    /// <p>[TransitionPane] publishes no "is animating" state, so this looks for the visible
    /// traces of a running animation: the pane holds two children (outgoing and incoming) and
    /// the incoming one is still partly transparent and/or still offset horizontally. A plain
    /// content swap leaves a single child at opacity 1 and translateX 0.</p>
    private static boolean isAnimating(ServerListPage page) {
        try {
            var field = ServerListPage.class.getDeclaredField("contentView");
            field.setAccessible(true);
            Object value = field.get(page);
            if (!(value instanceof org.jackhuang.hmcl.ui.animation.TransitionPane pane))
                return false;
            for (Node child : pane.getChildren())
                if (child.getOpacity() < 0.999 || Math.abs(child.getTranslateX()) > 0.5)
                    return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /// A snapshot of the pane's children and their animation state, for diagnostics.
    private static String describePane(ServerListPage page) {
        try {
            var field = ServerListPage.class.getDeclaredField("contentView");
            field.setAccessible(true);
            Object value = field.get(page);
            if (!(value instanceof org.jackhuang.hmcl.ui.animation.TransitionPane pane))
                return "<not a TransitionPane>";
            StringBuilder text = new StringBuilder("children=" + pane.getChildren().size());
            for (Node child : pane.getChildren())
                text.append(String.format(" [opacity=%.2f translateX=%.1f]",
                        child.getOpacity(), child.getTranslateX()));
            return text.toString();
        } catch (Throwable t) {
            return "<unreadable>";
        }
    }

    /// The pane's current size, as {width, height}.
    private static double[] paneSize(ServerListPage page) {
        try {
            var field = ServerListPage.class.getDeclaredField("contentView");
            field.setAccessible(true);
            Object value = field.get(page);
            if (value instanceof javafx.scene.layout.Region region)
                return new double[]{region.getWidth(), region.getHeight()};
        } catch (Throwable ignored) {
        }
        return new double[]{0, 0};
    }

    /// The first clickable whose label contains the given text.
    ///
    /// <p>Two kinds of clickable exist on this page and both must be found: the action rows are
    /// [org.jackhuang.hmcl.ui.construct.LineComponent]s (StackPanes whose title property holds
    /// the label), and the back buttons are plain {@link javafx.scene.control.Button}s. Firing
    /// an {@link javafx.event.ActionEvent} at a Label inside either does nothing — the handler
    /// lives on the clickable — so the clickable itself has to be returned.</p>
    private static Node findAction(Node node, String label) {
        if (node instanceof org.jackhuang.hmcl.ui.construct.LineComponent component
                && component.titleProperty() != null
                && component.titleProperty().get() != null
                && component.titleProperty().get().contains(label))
            return node;
        if (node instanceof javafx.scene.control.Button button
                && button.getText() != null && button.getText().contains(label))
            return node;
        if (node instanceof javafx.scene.Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findAction(child, label);
                if (found != null)
                    return found;
            }
        return null;
    }

    /// Every label text under a node.
    private static List<String> textIn(Node node) {
        List<javafx.scene.control.Label> labels = new java.util.ArrayList<>();
        collect(node, javafx.scene.control.Label.class, labels);
        List<String> texts = new java.util.ArrayList<>();
        for (var label : labels)
            if (label.getText() != null)
                texts.add(label.getText());
        return texts;
    }

    private static <T> List<T> nodesOfType(Node node, Class<T> type) {
        List<T> found = new java.util.ArrayList<>();
        collect(node, type, found);
        return found;
    }

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

    private static void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir))
            return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList())
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
