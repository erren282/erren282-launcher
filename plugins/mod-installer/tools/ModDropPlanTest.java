package org.dsh.modinstall;

import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.nio.file.Path;
import java.util.List;

/**
 * Verifies the drop rules: which drops the plugin owns, and when a multi-mod drop
 * is a coherent set rather than something the user must place by hand.
 */
public final class ModDropPlanTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        ownershipChecks();
        groupingChecks();
        dropTargetChecks();
        pageScopeChecks();

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * The regression that made the whole feature do nothing.
     *
     * <p>The launcher installs its global drop filters on the {@code Decorator},
     * which is an ancestor of the main page. The original check walked all the way
     * up, found that global handler, decided "this page handles drops itself" and
     * handed every drop back — the plugin was armed but never fired.</p>
     */
    private static void dropTargetChecks() {
        System.out.println();
        System.out.println("=== which node the drop landed on ===");

        // decorator (global handler) > rootPage > [mainPage handler] > page > deep child
        javafx.scene.layout.StackPane decorator = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane rootPage = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane mainPage = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane page = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane deepChild = new javafx.scene.layout.StackPane();
        decorator.getChildren().add(rootPage);
        rootPage.getChildren().add(mainPage);
        mainPage.getChildren().add(page);
        page.getChildren().add(deepChild);
        // The launcher's own global handler, exactly where DecoratorController puts it.
        decorator.setOnDragDropped(event -> { });
        // RootPage.getMainPage() installs a handler too, but its filter is
        // "modpack / NBT / JSON" — it never claims a mod.
        mainPage.setOnDragDropped(event -> { });

        // `claimed` mirrors that filter: a .jar mod is nobody's.
        java.util.function.Predicate<javafx.scene.Node> modNotClaimed =
                node -> node.getOnDragDropped() != null && false;
        java.util.function.Predicate<javafx.scene.Node> packClaimed =
                node -> node.getOnDragDropped() != null && true;

        check("a plain page inside the main page is ours",
                ModDropPlan.ownsDrop(page, rootPage, modNotClaimed));
        check("a deep child of that page is ours too",
                ModDropPlan.ownsDrop(deepChild, rootPage, modNotClaimed));
        check("the launcher's global handler on an ancestor does not block us",
                ModDropPlan.ownsDrop(deepChild, rootPage, modNotClaimed));
        // The second bug: the main page HAS a handler, but it does not want a mod.
        check("the main page's modpack/NBT/json handler does not block a mod",
                ModDropPlan.ownsDrop(deepChild, rootPage, modNotClaimed));
        check("a modpack IS claimed by that handler, so it is not ours",
                !ModDropPlan.ownsDrop(deepChild, rootPage, packClaimed));
        check("a node outside the main page is not ours",
                !ModDropPlan.ownsDrop(decorator, rootPage, modNotClaimed));
        check("no main page means no claim", !ModDropPlan.ownsDrop(page, null, modNotClaimed));
        check("no target means no claim", !ModDropPlan.ownsDrop(null, rootPage, modNotClaimed));

        // A page that really does handle drops keeps them.
        javafx.scene.layout.StackPane modListPage = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane modCell = new javafx.scene.layout.StackPane();
        modListPage.getChildren().add(modCell);
        modListPage.setOnDragDropped(event -> { });
        rootPage.getChildren().add(modListPage);
        check("a page that claims the files keeps the drop",
                !ModDropPlan.ownsDrop(modCell, rootPage, packClaimed));
        check("a sibling page that claims nothing is still ours",
                ModDropPlan.ownsDrop(page, rootPage, modNotClaimed));
    }

    /**
     * Drops outside the main page must be refused, not handled.
     *
     * <p>The launcher's own handler otherwise copies any {@code .jar}/{@code .zip}
     * into whichever instance is selected, with no compatibility check — a Fabric
     * mod could land in a Forge version. The plugin's rule is therefore: install on
     * the main page, refuse elsewhere, and leave pages that handle drops themselves
     * alone.</p>
     */
    private static void pageScopeChecks() {
        System.out.println();
        System.out.println("=== outside the main page: refuse, do not install ===");

        javafx.scene.layout.StackPane decorator = new Decorator();
        javafx.scene.layout.StackPane rootPage = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane mainPage = new javafx.scene.layout.StackPane();
        javafx.scene.layout.StackPane otherPage = new javafx.scene.layout.StackPane();
        decorator.getChildren().addAll(rootPage, otherPage);
        rootPage.getChildren().add(mainPage);
        decorator.setOnDragDropped(event -> { });

        // The main page: handled.
        check("a drop on the main page is inside it", insideMainPage(mainPage, rootPage));
        // Another page (settings, download, …): refused.
        check("a drop on another page is outside the main page",
                !insideMainPage(otherPage, rootPage));
        check("a drop on the decorator itself is outside the main page",
                !insideMainPage(decorator, rootPage));
        check("with no main page nothing is inside it", !insideMainPage(otherPage, null));

        // A page with its own drop handler keeps its drops, on or off the main page.
        javafx.scene.layout.StackPane modListPage = new javafx.scene.layout.StackPane();
        modListPage.setOnDragDropped(event -> { });
        javafx.scene.layout.StackPane cell = new javafx.scene.layout.StackPane();
        modListPage.getChildren().add(cell);
        otherPage.getChildren().add(modListPage);
        check("a page that handles drops itself is recognised",
                handlesDropsItself(cell, rootPage));
        check("a plain page does not claim drop handling",
                !handlesDropsItself(otherPage, rootPage));
        // The launcher's global handler sits on the decorator, above the main page,
        // and must not be mistaken for a page's own.
        check("the launcher's global handler above the main page does not count",
                !handlesDropsItself(mainPage.getChildren().isEmpty() ? mainPage : mainPage, rootPage));
    }

    /** Mirror of the plugin's main-page test. */
    private static boolean insideMainPage(javafx.scene.Node target, javafx.scene.Node rootPage) {
        if (target == null || rootPage == null) {
            return false;
        }
        for (javafx.scene.Node c = target; c != null; c = c.getParent()) {
            if (c == rootPage) {
                return true;
            }
        }
        return false;
    }

    /** Mirror of the plugin's own-handler test: stops at the main page or the global host. */
    private static boolean handlesDropsItself(javafx.scene.Node target, javafx.scene.Node rootPage) {
        for (javafx.scene.Node c = target; c != null; c = c.getParent()) {
            if (c == rootPage || isGlobalDropTarget(c)) {
                break;
            }
            if (c.getOnDragDropped() != null) {
                return true;
            }
        }
        return false;
    }

    /** The launcher nodes carrying the global drop filters. */
    private static boolean isGlobalDropTarget(javafx.scene.Node node) {
        return node.getClass().getSimpleName().equals("Decorator");
    }

    /** Stands in for the launcher's Decorator, which is where global filters live. */
    private static final class Decorator extends javafx.scene.layout.StackPane {
    }

    private static void ownershipChecks() {
        System.out.println("=== only the main page, and only when the page has no handler ===");
        List<Path> mods = List.of(Path.of("a.jar"));
        ModDropPlan.JarReader reader = file -> fabric("a", ">=1.20.1 <1.21");

        check("handled on the main page",
                ModDropPlan.decide(mods, true, false, reader) instanceof ModDropPlan.Decision.Handle);
        check("ignored on any other page",
                ModDropPlan.decide(mods, false, false, reader) instanceof ModDropPlan.Decision.Ignore);
        check("ignored when the page under the cursor handles drops itself",
                ModDropPlan.decide(mods, true, true, reader) instanceof ModDropPlan.Decision.Ignore);
        check("ignored when the drop carries no mods",
                ModDropPlan.decide(List.of(), true, false, reader) instanceof ModDropPlan.Decision.Ignore);
        check("an unreadable file is left to the launcher",
                ModDropPlan.decide(mods, true, false, file -> {
                    throw new java.io.IOException("not a zip");
                }) instanceof ModDropPlan.Decision.Ignore);
    }

    private static void groupingChecks() {
        System.out.println();
        System.out.println("=== multi-mod drops ===");

        ModJarInfo.Info fabricA = fabric("a", ">=1.20.1 <1.21");
        ModJarInfo.Info fabricB = fabric("b", ">=1.20.1 <1.21");
        ModJarInfo.Info fabricOther = fabric("c", ">=1.21");
        ModJarInfo.Info forgeSame = forge("d", "[1.20.1,1.21)");

        check("two Fabric mods for the same version are one set",
                ModDropPlan.sameTarget(List.of(fabricA, fabricB)));
        check("a single mod is always one set",
                ModDropPlan.sameTarget(List.of(fabricA)));
        check("the same version spelled identically still groups",
                ModDropPlan.sameTarget(List.of(
                        forge("a", "[1.20.1,1.21)"),
                        forge("b", "[1.20.1,1.21)"))));
        check("a differently spelled but equivalent range does not group",
                !ModDropPlan.sameTarget(List.of(
                        fabric("a", ">=1.20.1 <1.21"),
                        fabric("b", ">=1.20.1"))));
        check("different game versions are refused",
                !ModDropPlan.sameTarget(List.of(fabricA, fabricOther)));
        check("different loaders are refused",
                !ModDropPlan.sameTarget(List.of(fabricA, forgeSame)));
        check("the refusal message is the one the specification asks for",
                ModDropPlan.MIXED_MESSAGE.startsWith("不同版本，不同加载器的 mod"));

        // End to end through decide(): the refusal must name both differences.
        ModDropPlan.Decision decision = ModDropPlan.decide(
                List.of(Path.of("a.jar"), Path.of("c.jar")), true, false,
                file -> file.getFileName().toString().startsWith("a") ? fabricA : fabricOther);
        check("a mixed drop is refused", decision instanceof ModDropPlan.Decision.MixedLoaders);
        if (decision instanceof ModDropPlan.Decision.MixedLoaders mixed) {
            System.out.println("  message: " + mixed.message().replace("\n", " | "));
            check("the refusal explains what was detected",
                    mixed.message().contains("Fabric") && mixed.message().contains("1.21"));
        }
        System.out.println("  " + ModDropPlan.describeMismatch(List.of(fabricA, forgeSame))
                .replace("\n", " | "));

        // A drop of two mods that agree must come back as a set of both.
        ModDropPlan.Decision agreed = ModDropPlan.decide(
                List.of(Path.of("a.jar"), Path.of("b.jar")), true, false, file -> fabricB);
        check("an agreeing drop yields both mods",
                agreed instanceof ModDropPlan.Decision.Handle handle && handle.mods().size() == 2);
    }

    // ---------------------------------------------------------------- helpers

    private static ModJarInfo.Info fabric(String id, String spec) {
        return new ModJarInfo.Info(Path.of(id + ".jar"), ModLoaderType.FABRIC, id, id, "1.0",
                null, List.of(spec), List.of(), "", "");
    }

    private static ModJarInfo.Info forge(String id, String spec) {
        return new ModJarInfo.Info(Path.of(id + ".jar"), ModLoaderType.FORGE, id, id, "1.0",
                null, List.of(spec), List.of(), "", "");
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
