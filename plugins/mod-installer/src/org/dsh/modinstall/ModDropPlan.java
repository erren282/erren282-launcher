package org.dsh.modinstall;

import org.jackhuang.hmcl.addon.mod.ModLoaderType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what a drop of mod files should do, before any UI is involved.
 *
 * <p>Kept free of JavaFX so the rules can be tested directly: which drops the
 * plugin owns at all, whether a multi-mod drop is a single coherent set, and how
 * the dropped mods line up with the installed game versions.</p>
 */
final class ModDropPlan {

    /** What to do with a drop. */
    sealed interface Decision {

        /** Not ours: no mod files, or the drop belongs to the current page. */
        record Ignore() implements Decision {
        }

        /** Mods that disagree on version or loader — the user must place them. */
        record MixedLoaders(String message) implements Decision {
        }

        /** One coherent set of mods to handle. */
        record Handle(List<ModJarInfo.Info> mods) implements Decision {
        }
    }

    /** The message the specification asks for on an incoherent drop. */
    static final String MIXED_MESSAGE =
            "不同版本，不同加载器的 mod 请自行放至对应版本的游戏 mod 文件夹中。";

    /** The message shown when the user has no matching game version. */
    static final String NO_VERSION_MESSAGE = "你并没有对应的游戏版本，是否前去下载？";

    private ModDropPlan() {
    }

    /**
     * Classifies a drop.
     *
     * @param mods            the dropped files that look like mods
     * @param onMainPage      whether the launcher is showing its main page
     * @param targetHandlesDrops whether the page under the cursor has its own handler
     * @param reader          reads one dropped file
     */
    static Decision decide(List<Path> mods, boolean onMainPage, boolean targetHandlesDrops,
                           JarReader reader) {
        // The feature is explicitly main-page only, and a page with its own drop
        // handler (the mod list) knows better than we do.
        if (!onMainPage || targetHandlesDrops) {
            return new Decision.Ignore();
        }
        if (mods.isEmpty()) {
            return new Decision.Ignore();
        }

        List<ModJarInfo.Info> infos = new ArrayList<>();
        for (Path mod : mods) {
            try {
                infos.add(reader.read(mod));
            } catch (Exception e) {
                // An unreadable file is still offered, as an unknown mod.
                return new Decision.Ignore();
            }
        }

        if (!sameTarget(infos)) {
            // Explain *what* differed: with several files in the drop, the plain
            // sentence alone leaves the user guessing which one is the odd one out.
            return new Decision.MixedLoaders(describeMismatch(infos));
        }
        return new Decision.Handle(infos);
    }

    /**
     * True when every mod targets the same loader and the same declared game
     * versions — the condition the specification puts on a multi-mod drop.
     */
    static boolean sameTarget(List<ModJarInfo.Info> mods) {
        if (mods.size() <= 1) {
            return true;
        }
        ModLoaderType loader = mods.get(0).loader();
        List<String> signature = signatureOf(mods.get(0));
        for (ModJarInfo.Info mod : mods) {
            if (mod.loader() != loader) {
                return false;
            }
            if (!signatureOf(mod).equals(signature)) {
                return false;
            }
        }
        return true;
    }

    /** The normalised game-version declaration, used as the grouping key. */
    private static List<String> signatureOf(ModJarInfo.Info mod) {
        Set<String> specs = new LinkedHashSet<>();
        for (String spec : mod.mcVersionSpecs()) {
            String trimmed = spec == null ? "" : spec.trim();
            if (!trimmed.isEmpty()) {
                specs.add(trimmed);
            }
        }
        return new ArrayList<>(specs);
    }

    /** A reason the drop was rejected, phrased for the dialog. */
    static String describeMismatch(List<ModJarInfo.Info> mods) {
        Set<String> loaders = new LinkedHashSet<>();
        Set<String> versions = new LinkedHashSet<>();
        for (ModJarInfo.Info mod : mods) {
            loaders.add(mod.loaderName());
            versions.add(mod.mcVersionSpecs().isEmpty()
                    ? "未声明" : String.join(" ", mod.mcVersionSpecs()));
        }
        return MIXED_MESSAGE + "\n\n检测到：" + String.join(" / ", loaders)
                + "；游戏版本 " + String.join(" / ", versions);
    }

    /**
     * True when the plugin should take over a drop that landed on {@code target}.
     *
     * <p>Two conditions, both about the node the cursor is over:</p>
     * <ol>
     *   <li>the target is inside the launcher's main page;</li>
     *   <li>no handler between the target and the main page <em>claims these
     *       files</em>.</li>
     * </ol>
     *
     * <p><b>Why "claims these files" and not "has a handler".</b> The main page
     * itself installs a drop handler ({@code FXUtils.applyDragListener} in
     * {@code RootPage.getMainPage()}), and the launcher's global {@code PluginDnD}
     * filter sits on the {@code Decorator} above the main page. Both check only
     * <em>whether a handler exists</em>, so:</p>
     * <ul>
     *   <li>an ancestor's global handler made an existence check always true,
     *       which is what silenced the first version of this feature;</li>
     *   <li>the main page's handler exists but accepts only modpacks, NBT and
     *       JSON — it never claims a {@code .jar} mod. Treating its mere presence
     *       as "the page wants this" means a dragged mod is claimed by nobody, so
     *       nothing happens at all.</li>
     * </ul>
     *
     * <p>The walk therefore stops at the main page, and a handler counts only when
     * its own filter would accept at least one of the dropped files.</p>
     *
     * @param target   the node the drop landed on
     * @param rootPage the launcher's main page, or null when it is not available
     * @param claimed  reports whether a node's own handler accepts the dropped files
     */
    static boolean ownsDrop(javafx.scene.Node target, javafx.scene.Node rootPage,
                            java.util.function.Predicate<javafx.scene.Node> claimed) {
        if (target == null || rootPage == null) {
            return false;
        }
        // Condition 1: the target must sit inside the main page.
        boolean insideMainPage = false;
        for (javafx.scene.Node current = target; current != null; current = current.getParent()) {
            if (current == rootPage) {
                insideMainPage = true;
                break;
            }
        }
        if (!insideMainPage) {
            return false;
        }
        // Condition 2: nothing between the target and the main page claims the files.
        for (javafx.scene.Node current = target; current != null; current = current.getParent()) {
            if (current == rootPage) {
                break;
            }
            if (claimed.test(current)) {
                return false;
            }
        }
        return true;
    }

    /** Reads one dropped file; injectable so the rules can be tested. */
    interface JarReader {
        ModJarInfo.Info read(Path file) throws Exception;
    }
}
