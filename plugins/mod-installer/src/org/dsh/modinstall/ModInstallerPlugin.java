package org.dsh.modinstall;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.plugin.PluginDnD;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.nbt.NBTFileType;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Installs dropped mods into a game version the user picks.
 *
 * <h2>How it hooks in without changing the launcher</h2>
 * <p>The launcher's own drop handling lives in {@link PluginDnD}, which quietly
 * copies a dropped mod into whichever instance is selected. This plugin installs
 * an <em>event filter on the scene</em> instead of replacing that code: filters run
 * during the capturing phase, so this one sees the drop first and can consume it
 * before the launcher's handler acts. Nothing in the launcher has to change.</p>
 *
 * <h2>When it stays out of the way</h2>
 * <ul>
 *   <li>only on the main page — the drop target must sit inside
 *       {@link Controllers#getRootPage()};</li>
 *   <li>only when the page under the cursor has no drop handling of its own (the
 *       mod list page knows better than this plugin);</li>
 *   <li>only for files that actually look like game mods: plugin jars and modpack
 *       archives keep going to their existing handlers.</li>
 * </ul>
 */
public final class ModInstallerPlugin extends HmclPlugin {

    private Scene scene;
    private EventHandler<DragEvent> dragOver;
    private EventHandler<DragEvent> dragDropped;
    private boolean disabled;
    private javafx.animation.Timeline scenePoll;

    @Override
    public void onEnable() {
        getContext().logInfo("拖入安装 Mod 已启用：在主页面拖入 Mod 文件即可选择游戏版本安装");
        // Plugins are loaded before the launcher builds its scene, so wait for it.
        waitForScene();
    }

    @Override
    public void onDisable() {
        disabled = true;
        if (scenePoll != null) {
            scenePoll.stop();
            scenePoll = null;
        }
        detach();
    }

    // ------------------------------------------------------------------ wiring

    /**
     * Waits for the launcher's scene, then attaches the filters.
     *
     * <p>A timer rather than a chain of {@code runLater} calls: chained runnables
     * drain within a single pulse, so the whole retry budget was spent in the
     * first second of startup — before {@code Controllers.initialize} had created
     * the scene — and the feature silently never armed.</p>
     */
    private void waitForScene() {
        Scene current = Controllers.getScene();
        if (current != null) {
            attach(current);
            return;
        }
        scenePoll = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(150), event -> {
            if (disabled) {
                return;
            }
            Scene ready = Controllers.getScene();
            if (ready != null) {
                scenePoll.stop();
                scenePoll = null;
                attach(ready);
            }
        }));
        // ~60 seconds, comfortably past a slow cold start.
        scenePoll.setCycleCount(400);
        scenePoll.setOnFinished(event -> {
            if (scene == null && !disabled) {
                getContext().logWarning("未能取得启动器窗口，拖入安装 Mod 未生效");
            }
        });
        scenePoll.play();
    }

    /** Installs the drag filters on a live scene. */
    private void attach(Scene target) {
        scene = target;
        // The cursor is offered a copy on the main page, where the mod will be
        // handled; elsewhere the drop is refused on release, so no copy cursor is
        // advertised there.
        dragOver = event -> {
            if (isOnMainPage(event) && !modFilesOf(event.getDragboard()).isEmpty()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
        };
        dragDropped = this::onDropped;
        scene.addEventFilter(DragEvent.DRAG_OVER, dragOver);
        scene.addEventFilter(DragEvent.DRAG_DROPPED, dragDropped);
        getContext().logInfo("已接手 Mod 拖入（主页面安装，其他页面拦截）");
    }

    private void detach() {
        if (scene != null) {
            if (dragOver != null) {
                scene.removeEventFilter(DragEvent.DRAG_OVER, dragOver);
            }
            if (dragDropped != null) {
                scene.removeEventFilter(DragEvent.DRAG_DROPPED, dragDropped);
            }
        }
        scene = null;
        dragOver = null;
        dragDropped = null;
    }

    // ------------------------------------------------------------- the drop

    private void onDropped(DragEvent event) {
        // Trace every drop that reaches us. Without this, a drop the plugin
        // declines is indistinguishable in the log from a drop that never arrived,
        // which makes "it does nothing" impossible to diagnose from a user report.
        try {
            List<Path> mods = modFilesOf(event.getDragboard());
            if (mods.isEmpty()) {
                // Not a game mod: modpacks, NBT, JSON and everything else keep
                // going to the launcher's own handlers, on every page.
                return;
            }
            boolean onMainPage = isOnMainPage(event);
            getContext().logInfo("收到拖入：文件 " + allFilesOf(event.getDragboard()).size()
                    + " 个，其中 Mod " + mods.size() + " 个，"
                    + (onMainPage ? "主页面，本插件接管" : "非主页面，拦截"));

            if (!onMainPage) {
                block(event, mods);
                return;
            }

            ModDropPlan.Decision decision = ModDropPlan.decide(mods, true, false, ModJarInfo::read);
            if (decision instanceof ModDropPlan.Decision.Ignore) {
                getContext().logInfo("Mod 无法识别（缺少元数据？），交回启动器处理");
                return;
            }

            // Take the event: otherwise the launcher would also copy the files into
            // the selected instance, behind the dialog the user is about to see.
            event.setDropCompleted(true);
            event.consume();

            if (decision instanceof ModDropPlan.Decision.MixedLoaders mixed) {
                getContext().logInfo("多个 Mod 的版本/加载器不一致，已提示用户");
                Platform.runLater(() -> Controllers.dialog(mixed.message(), "无法一起安装",
                        MessageDialogPane.MessageType.WARNING));
                return;
            }
            if (decision instanceof ModDropPlan.Decision.Handle handle) {
                List<Path> files = mods;
                getContext().logInfo("显示安装对话框：" + handle.mods().stream()
                        .map(ModJarInfo.Info::name).toList());
                Platform.runLater(() -> ModInstallDialog.show(handle.mods(), files));
            }
        } catch (Throwable t) {
            getContext().logError("处理拖入的 Mod 失败", t);
        }
    }

    /**
     * Refuses a mod dropped anywhere but the main page.
     *
     * <p>Without this the launcher's own handler takes over and copies the mod
     * into whichever instance is selected — with no compatibility check at all, so
     * a Fabric mod could be dropped into a Forge version, or a 1.21 mod into
     * 1.7.10. The feature is main-page-only by design, and "does nothing" is the
     * specified behaviour elsewhere; silently installing the wrong thing is not.
     * The event is consumed so that handler never runs.</p>
     */
    private void block(DragEvent event, List<Path> mods) {
        // A page with its own drop handling (the mod list, the version page) is
        // left alone: it knows what to do with these files.
        if (hasOwnDropHandler(event)) {
            getContext().logInfo("当前页面自己处理拖放，交还给它");
            return;
        }
        event.setDropCompleted(true);
        event.consume();
        String what = mods.size() == 1
                ? mods.get(0).getFileName().toString()
                : mods.size() + " 个 Mod";
        getContext().logInfo("已拦截非主页面的 Mod 拖入：" + what);
        Platform.runLater(() -> Controllers.showToast(
                "请回到主页面再拖入 Mod：" + what
                        + "（在其他页面拖入会被安装到当前选中的版本，可能不兼容）"));
    }

    /** True when the drop landed inside the launcher's main page. */
    private static boolean isOnMainPage(DragEvent event) {
        if (!(event.getTarget() instanceof Node target)) {
            return false;
        }
        Node rootPage = Controllers.getRootPage();
        if (rootPage == null) {
            return false;
        }
        for (Node current = target; current != null; current = current.getParent()) {
            if (current == rootPage) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the page under the cursor declares its own drop handling.
     *
     * <p>The walk must not run into the launcher's <em>global</em> drop filters,
     * which sit on the {@code Decorator} — an ancestor of every page. Two bounds
     * are therefore needed:</p>
     * <ul>
     *   <li>stop at the main page when the target is inside it (the main page's own
     *       handler is a page handler, and it is deliberately not counted — it only
     *       accepts modpacks/NBT/JSON);</li>
     *   <li>stop at the {@code Decorator} otherwise, since everything above it is
     *       launcher chrome rather than a page.</li>
     * </ul>
     *
     * <p>Without the second bound, every drop on a non-main page looked like "this
     * page handles it", {@link #block} always deferred, and the launcher's own
     * handler silently installed the mod into the selected version — the bug this
     * method exists to prevent.</p>
     */
    private static boolean hasOwnDropHandler(DragEvent event) {
        if (!(event.getTarget() instanceof Node target)) {
            return false;
        }
        Node rootPage = Controllers.getRootPage();
        for (Node current = target; current != null; current = current.getParent()) {
            if (current == rootPage || isGlobalDropTarget(current)) {
                break;
            }
            if (current.getOnDragDropped() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for the launcher nodes that carry the global drop filters: the
     * {@code Decorator} (and its content wrapper) rather than a page.
     *
     * <p>Detected by class name so this plugin does not have to depend on the
     * launcher's internal decorator types, which are not part of the plugin API.
     * The launcher installs its filters on the decorator precisely; a page is
     * always a descendant of it.</p>
     */
    private static boolean isGlobalDropTarget(Node node) {
        String name = node.getClass().getSimpleName();
        return name.equals("Decorator");
    }

    /** The dropped files that look like game mods. */
    private static List<Path> modFilesOf(Dragboard board) {
        List<Path> mods = new ArrayList<>();
        if (board == null || !board.hasFiles()) {
            return mods;
        }
        for (File file : board.getFiles()) {
            Path path = file.toPath();
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Path name = path.getFileName();
            if (name == null) {
                continue;
            }
            String text = name.toString().toLowerCase(Locale.ROOT);
            int dot = text.lastIndexOf('.');
            String extension = dot < 0 ? "" : text.substring(dot + 1);
            if (!ModManager.MOD_EXTENSIONS.contains(extension)) {
                continue;
            }
            // A launcher plugin is installed by the launcher, not by us.
            if (PluginDnD.isPluginJar(path)) {
                continue;
            }
            // A modpack is a whole instance, not a single mod.
            if (ModpackHelper.isFileModpackByExtension(path)) {
                continue;
            }
            mods.add(path);
        }
        return mods;
    }

    /**
     * Every regular file on the dragboard, whatever its extension.
     *
     * <p>Used for the "does some page claim this?" test, which must see the whole
     * drop — not just the files that already look like mods.</p>
     */
    private static List<Path> allFilesOf(Dragboard board) {
        List<Path> files = new ArrayList<>();
        if (board == null || !board.hasFiles()) {
            return files;
        }
        for (File file : board.getFiles()) {
            Path path = file.toPath();
            if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        return files;
    }

    /** A file's extension, lower case, without the dot. */
    private static String extensionOf(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String text = name.toString();
        int dot = text.lastIndexOf('.');
        return dot < 0 || dot == text.length() - 1 ? "" : text.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
