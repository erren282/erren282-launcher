package org.dsh.modtoversion;

import com.jfoenix.controls.JFXDialog;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.plugin.HmclPlugin;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.JFXDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.versions.DownloadPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Adds a 「下载到对应版本」 button to the launcher's content download dialogs — mods,
 * resource packs, shader packs and worlds.
 *
 * <h2>Why it has to be done this way</h2>
 * <p>The dialog is a private class ({@code DownloadPage$AddonVersion}): no id, no style
 * class, and no field holding the release it shows. It cannot be found by type or by
 * lookup. Three publicly reachable things make it possible anyway:</p>
 * <ul>
 *   <li>{@code Controllers.getDecorator().getDecorator().getDrawerWrapper()} is the
 *       public {@link StackPane} dialogs are shown in — {@code JFXDialog.show()} puts
 *       the dialog into its children;</li>
 *   <li>the dialog's content is the {@link JFXDialogPane} that {@code DialogUtils}
 *       pushes content onto;</li>
 *   <li>every dialog built on {@code JFXDialogLayout} exposes {@code getActions()}, the
 *       row the launcher's own buttons live in.</li>
 * </ul>
 *
 * <h2>The ordering that matters</h2>
 * <p>{@code DialogUtils.show} sets the dialog's content, then calls {@code dialog.show()}
 * (which adds the dialog to the wrapper), and only then calls
 * {@code dialogPane.push(content)}. So when the dialog appears in the wrapper its pane
 * is still empty — the pane's own children listener is what catches the content. Waiting
 * for {@code DialogUtils}' published pane property instead does not work: that property
 * is only written the first time a dialog is shown, so at startup it never appears.</p>
 */
public final class ModToVersionPlugin extends HmclPlugin {

    /// Dialog layouts already given a button, so it is never added twice.
    private final Set<Node> handled = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Dialog panes already watched.
    private final Set<Node> watchedPanes = Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean attached;

    @Override
    public void onEnable() {
        getContext().logInfo("下载内容到指定版本已启用：模组、资源包、光影、世界的下载弹窗"
                + "都将多出一个「" + AddonDialogInjector.BUTTON_TEXT + "」按钮");
        reportRecognisedHeadings();
        watchDialogs(0);
    }

    @Override
    public void onDisable() {
        attached = false;
        handled.clear();
        watchedPanes.clear();
    }

    /// Logs the heading shapes the plugin will look for in the current locale.
    ///
    /// <p>Without this, a launcher running a locale whose template changed shape would
    /// simply never show the button, with nothing in the log to explain why.</p>
    private void reportRecognisedHeadings() {
        StringBuilder line = new StringBuilder("将识别的下载弹窗标题：");
        for (AddonKind kind : AddonKind.values()) {
            DialogHeader.Heading heading = DialogHeader.headingOf(kind);
            if (heading == null) {
                line.append("\n  ").append(kind.name()).append(": 无法从语言文件读取标题");
            } else {
                line.append("\n  ").append(kind.name()).append(": ")
                        .append(heading.prefix()).append("<名称>").append(heading.suffix());
            }
        }
        getContext().logInfo(line.toString());
    }

    // --------------------------------------------------------------- watching

    /**
     * Waits for the dialog container, then watches it for dialogs.
     *
     * <p>A chain of {@code runLater} calls would all run in the same pulse, long before
     * the launcher finishes building its window, so this retries on a 200 ms timer
     * instead.</p>
     */
    private void watchDialogs(int attempt) {
        if (attached) {
            return;
        }
        StackPane wrapper = drawerWrapper();
        if (wrapper == null) {
            if (attempt < 200) {
                javafx.animation.Timeline retry = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.millis(200),
                                event -> watchDialogs(attempt + 1)));
                retry.setCycleCount(1);
                retry.play();
            } else {
                getContext().logWarning("未能取得启动器弹窗容器，「"
                        + AddonDialogInjector.BUTTON_TEXT + "」按钮未生效");
            }
            return;
        }

        wrapper.getChildren().addListener(
                (javafx.collections.ListChangeListener<Node>) change -> {
                    while (change.next()) {
                        for (Node added : change.getAddedSubList()) {
                            if (added instanceof JFXDialog dialog) {
                                watchPaneOf(dialog);
                            }
                        }
                    }
                });
        // A dialog may already be open.
        for (Node child : new ArrayList<>(wrapper.getChildren())) {
            if (child instanceof JFXDialog dialog) {
                watchPaneOf(dialog);
            }
        }
        attached = true;
        getContext().logInfo("已开始监视模组、资源包、光影、世界的下载弹窗");
    }

    /** Watches the pane a dialog pushes its content onto. */
    private void watchPaneOf(JFXDialog dialog) {
        if (!(dialog.getContent() instanceof JFXDialogPane pane) || !watchedPanes.add(pane)) {
            return;
        }
        pane.getChildren().addListener(
                (javafx.collections.ListChangeListener<Node>) change -> {
                    while (change.next()) {
                        for (Node added : change.getAddedSubList()) {
                            inject(added);
                        }
                    }
                });
        for (Node child : new ArrayList<>(pane.getChildren())) {
            inject(child);
        }
    }

    /** The dialog container the launcher shows dialogs in, or null before it exists. */
    private static StackPane drawerWrapper() {
        try {
            var controller = Controllers.getDecorator();
            if (controller == null) {
                return null;
            }
            return controller.getDecorator().getDrawerWrapper();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- injection

    /** Adds the button when {@code node} is one of the supported download dialogs. */
    private void inject(Node node) {
        if (node == null || handled.contains(node)) {
            return;
        }
        // Deferred by one pulse: the launcher sets its actions inside the dialog's
        // constructor, and the pane may publish the node just before that returns.
        Platform.runLater(() -> {
            if (handled.contains(node)) {
                return;
            }
            try {
                if (AddonDialogInjector.tryInject(node, this::onDownloadToVersion)) {
                    handled.add(node);
                    Optional<AddonKind> kind = AddonDialogInjector.describeKind(node);
                    getContext().logInfo("已在" + kind.map(AddonKind::noun).orElse("内容")
                            + "下载弹窗中加入「" + AddonDialogInjector.BUTTON_TEXT + "」按钮");
                }
            } catch (Throwable t) {
                getContext().logWarning("加入按钮失败：" + t);
            }
        });
    }

    // ---------------------------------------------------------------- action

    /**
     * Asks where to put it, then installs the release into that version.
     *
     * <h2>Why this is normally instant</h2>
     * <p>Opening an addon's page makes the launcher download that addon's whole version
     * list and keep it in the page. The release the dialog is showing is in that list,
     * so it is read straight out of memory — no network at all. Only when the page's
     * list is not ready, or does not contain this release, does this fall back to
     * fetching (measured at 0.6–2 s), and then the user is told so immediately rather
     * than watching a dead click.</p>
     */
    private void onDownloadToVersion(AddonKind kind, String releaseName) {
        DownloadPage page = currentPage();
        RemoteAddon addon = page == null ? null : page.getAddon();
        if (addon == null) {
            Controllers.dialog("无法确定这个" + kind.noun() + "的来源，请改用「下载到本地文件夹」。",
                    "无法下载", MessageDialogPane.MessageType.WARNING);
            return;
        }

        // The fast path: a field read on the page's already-loaded list.
        List<RemoteAddon.Version> cached = LoadedReleases.releasesOf(page);
        Optional<RemoteAddon.Version> known =
                ModToVersionEngine.findReleaseByName(cached, releaseName);
        if (known.isPresent()) {
            showPicker(kind, known.get());
            return;
        }

        // The slow path. Say so at once: without this the click looks ignored for a
        // second or two.
        final RemoteAddon source = addon;
        getContext().logInfo("页面的版本列表中没有「" + releaseName + "」，改为在线获取");
        Controllers.showToast("正在获取「" + addon.title() + "」的版本信息…");
        Thread worker = new Thread(() -> {
            Optional<RemoteAddon.Version> fetched;
            try {
                fetched = ModToVersionEngine.resolveRelease(List.of(), source, releaseName, kind);
            } catch (Throwable t) {
                fetched = Optional.empty();
            }
            Optional<RemoteAddon.Version> result = fetched;
            Platform.runLater(() -> {
                if (result.isEmpty()) {
                    Controllers.dialog(
                            "没有在「" + source.title() + "」里找到版本「" + releaseName + "」。\n"
                                    + "可能是网络问题，或该版本已从下载源移除。",
                            "找不到版本", MessageDialogPane.MessageType.WARNING);
                } else {
                    showPicker(kind, result.get());
                }
            });
        }, "mod-to-version-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    /** Offers the versions a release fits, or explains why none do. */
    private void showPicker(AddonKind kind, RemoteAddon.Version release) {
        List<VersionMatcher.Installed> targets = VersionMatcher.fitting(
                ModToVersionEngine.installedVersions(), release, kind);
        if (targets.isEmpty()) {
            TargetVersionDialog.showNothingFits(kind, release);
        } else {
            TargetVersionDialog.show(kind, release, targets,
                    target -> install(kind, release, target));
        }
    }

    /**
     * The addon detail page currently open.
     *
     * <p>The dialog itself carries no reference to its addon, but it is only ever opened
     * from that page, so the current page is the addon. Its {@code getAddon()} is
     * public, and the page also holds the loaded release list this uses for the fast
     * path.</p>
     */
    private static DownloadPage currentPage() {
        try {
            for (Node node : Controllers.getDecorator().getDecorator().getContent()) {
                if (node instanceof org.jackhuang.hmcl.ui.construct.Navigator navigator) {
                    Node page = navigator.getCurrentPage();
                    if (page instanceof DownloadPage detail) {
                        return detail;
                    }
                }
            }
        } catch (Throwable t) {
            // Fall through: no page could be determined.
        }
        return null;
    }

    /**
     * Installs the release into the chosen version.
     *
     * <p>Mods, resource packs and shaders go through the launcher's own downloader with
     * the same target directory the launcher uses, so the file lands in the right place
     * and the launcher's post-download handling runs. A world is unpacked into
     * {@code saves} instead — see [AddonInstaller].</p>
     */
    private void install(AddonKind kind, RemoteAddon.Version release,
                         VersionMatcher.Installed target) {
        HMCLGameRepository repository;
        try {
            repository = GameDirectoryManager.getSelectedRepository();
        } catch (Throwable t) {
            getContext().logError("无法取得选中的游戏目录", t);
            Controllers.dialog("无法取得选中的游戏目录。", "无法下载",
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        if (repository == null) {
            Controllers.dialog("没有选中的游戏目录。", "无法下载",
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        // The dialog may be showing one build while the chosen version needs another
        // (a Forge instance and a Fabric build). Prefer the release that fits.
        RemoteAddon.Version chosen = VersionMatcher.releaseFor(
                List.of(release), release, target, kind);
        if (chosen == null) {
            Controllers.dialog("「" + release.name() + "」不能安装到「" + target.id() + "」。",
                    "无法安装", MessageDialogPane.MessageType.WARNING);
            return;
        }
        if (chosen != release) {
            getContext().logInfo("「" + release.name() + "」不适用于「" + target.id()
                    + "」，改用「" + chosen.name() + "」");
        }

        Controllers.showToast("正在把「" + chosen.name() + "」下载到「" + target.id() + "」");
        AddonInstaller.install(kind, ModToVersionEngine.downloadProvider(), repository,
                target.id(), chosen, new AddonInstaller.Reporter() {
                    @Override
                    public void info(String message) {
                        getContext().logInfo(message);
                    }

                    @Override
                    public void error(String message, Throwable throwable) {
                        getContext().logError(message, throwable);
                    }
                });
    }
}
