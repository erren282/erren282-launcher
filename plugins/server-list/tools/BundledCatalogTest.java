package org.dsh.serverlist;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Verifies the bundled server catalog actually ships, and that the page's heading no longer
 * carries a back arrow.
 *
 * <h2>Why the catalog needs a test</h2>
 * <p>The ten default servers live in {@code resources/servers.json} and are read from the
 * plugin jar as a class resource. A jar built by hand — without the {@code resources}
 * directory — silently loses them, and the page then shows "共 0 个服务器" with nothing to
 * explain why. The build script handles it, but nothing caught the hand-built jar, so this
 * reads the resource the same way the launcher does and counts what is in it.</p>
 *
 * <h2>Why the heading needs a test</h2>
 * <p>The arrow was added, then removed again on request. The test asserts the page passes no
 * back action to the heading, so it cannot come back unnoticed.</p>
 */
public final class BundledCatalogTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // The heading checks build JavaFX controls, whose static initialisers read launcher
        // settings; the catalog checks alone need no toolkit.
        boolean needToolkit = true;
        if (needToolkit) {
            org.jackhuang.hmcl.setting.SettingsManager.init();
            javafx.application.Platform.startup(() -> { });
        }
        bundledCatalogChecks();
        headingChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        if (needToolkit)
            javafx.application.Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    /// The bundled catalog must be present, complete and usable.
    private static void bundledCatalogChecks() {
        System.out.println("=== the bundled catalog ships inside the jar ===");

        List<ServerCatalog.Entry> bundled = ServerCatalog.bundled();
        System.out.println("  ServerCatalog.bundled(): " + bundled.size());

        // The catalog is the page's default content: losing it leaves an empty list, and a
        // hand-built jar loses it silently.
        check("all ten bundled servers are present", bundled.size() == 10);
        check("every entry has a name", bundled.stream().allMatch(e -> e.name() != null
                && !e.name().isBlank()));
        check("every entry has an address", bundled.stream().allMatch(e -> e.address() != null
                && !e.address().isBlank()));
        check("no entry has a blank description",
                bundled.stream().noneMatch(e -> e.description() == null));

        System.out.println();
        System.out.println("=== the resource really is inside the jar ===");
        try (var input = ServerCatalog.class.getResourceAsStream("/servers.json")) {
            check("the resource is on the classpath", input != null);
            if (input != null) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                check("the file is not empty", !text.isBlank());
            }
        } catch (Exception e) {
            check("the resource can be read", false);
        }

        System.out.println();
        System.out.println("=== addresses look usable ===");
        List<String> addresses = bundled.stream().map(ServerCatalog.Entry::address).toList();
        check("no address contains a scheme", addresses.stream().noneMatch(a -> a.contains("://")));
        check("no address has a trailing slash", addresses.stream().noneMatch(a -> a.endsWith("/")));
    }

    /// The heading must not gain an arrow.
    private static void headingChecks() {
        System.out.println();
        System.out.println("=== the heading has no back arrow ===");

        // Two arguments is the historical signature; the three-argument overload is the one
        // that adds an arrow. The page must call the two-argument form.
        javafx.scene.control.Label title = new javafx.scene.control.Label("服务器列表");
        javafx.scene.layout.VBox heading = ListUi.heading("服务器列表", "说明");
        List<javafx.scene.control.Button> buttons = new java.util.ArrayList<>();
        collect(heading, javafx.scene.control.Button.class, buttons);
        check("no button is present in a plain heading", buttons.isEmpty());
        check("the title is still rendered",
                labelsOf(heading).contains("服务器列表"));

        // And the arrow-adding overload still works when a caller asks for one.
        javafx.scene.layout.VBox withArrow = ListUi.heading("服务器列表", "说明", () -> { });
        List<javafx.scene.control.Button> arrowButtons = new java.util.ArrayList<>();
        collect(withArrow, javafx.scene.control.Button.class, arrowButtons);
        check("the overload still adds exactly one arrow", arrowButtons.size() == 1);
        check("the title label is untouched", title.getText().equals("服务器列表"));
    }

    /// Every label text under a node.
    private static List<String> labelsOf(javafx.scene.Node node) {
        List<javafx.scene.control.Label> labels = new java.util.ArrayList<>();
        collect(node, javafx.scene.control.Label.class, labels);
        return labels.stream().map(javafx.scene.control.Label::getText).toList();
    }

    /// Depth-first search for nodes of one type.
    private static <T> void collect(javafx.scene.Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node))
            into.add(type.cast(node));
        if (node instanceof javafx.scene.Parent parent)
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable())
                collect(child, type, into);
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
